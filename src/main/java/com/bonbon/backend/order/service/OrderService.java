package com.bonbon.backend.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.account.DeliveryAddresses;
import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.GeoDistance;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.merchant.ShopOrdering.OrderableDish;
import com.bonbon.backend.merchant.ShopOrdering.OrderableGroup;
import com.bonbon.backend.merchant.ShopOrdering.OrderableOption;
import com.bonbon.backend.merchant.ShopOrdering.OrderableShop;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.dto.OrderRequests;
import com.bonbon.backend.order.dto.OrderViews;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.entity.OrderItem;
import com.bonbon.backend.order.entity.OrderStatusHistory;
import com.bonbon.backend.order.repository.OrderRepository;
import com.bonbon.backend.order.repository.OrderStatusHistoryRepository;
import com.bonbon.backend.order.repository.ReviewRepository;
import com.bonbon.backend.payment.OnlinePayments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A customer placing and reading their own orders (place-order.md, view-order-history.md, track-order.md).
 * Everything is re-validated and priced on the server, then copied into the order as a snapshot.
 */
@Service
public class OrderService {

    static final String MAX_OPEN_ORDERS_KEY = "abuse.max_open_orders";
    static final String DEFAULT_COMMISSION_KEY = "commission.default_rate";
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MIN_ONLINE = 1_000;
    private static final int MAX_ONLINE = 50_000_000;

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ShopOrdering shops;
    private final DeliveryAddresses addresses;
    private final CategoryCatalog categories;
    private final SystemSettingsService settings;
    private final OrderTransitions transitions;
    private final ReviewRepository reviews;
    private final OnlinePayments payments;
    private final OrderSettings orderSettings;
    private final TransactionTemplate tx;

    OrderService(OrderRepository orders, OrderStatusHistoryRepository history, ShopOrdering shops, DeliveryAddresses addresses,
            CategoryCatalog categories, SystemSettingsService settings, OrderTransitions transitions,
            ReviewRepository reviews, OnlinePayments payments, OrderSettings orderSettings, PlatformTransactionManager transactions) {
        this.orders = orders;
        this.history = history;
        this.shops = shops;
        this.addresses = addresses;
        this.categories = categories;
        this.settings = settings;
        this.transitions = transitions;
        this.reviews = reviews;
        this.payments = payments;
        this.orderSettings = orderSettings;
        this.tx = new TransactionTemplate(transactions);
    }

    /** {@code created} is false when the same Idempotency-Key was used before: the first order comes back unchanged. */
    public record Placed(OrderViews.Detail order, boolean created) {
    }

    public Placed place(CurrentPrincipal customer, String idempotencyKey, OrderRequests.Place req, ClientContext client,
            String userAgent) {
        var existing = tx.execute(status -> orders.findByCustomerIdAndIdempotencyKey(customer.id(), idempotencyKey)
                .map(this::detail));
        if (existing != null && existing.isPresent()) {
            return new Placed(existing.get(), false);
        }
        try {
            OrderViews.Detail created = tx.execute(status -> create(customer.id(), idempotencyKey, req, client, userAgent));
            if ("ONLINE".equals(req.paymentMethod())) {
                created = startPayment(created.id(), created.number(), created.totals().grandTotal(), created.placedAt());
            }
            return new Placed(created, true);
        } catch (DataIntegrityViolationException e) {
            // A double tap raced us: the other request won the unique (customer, key) pair.
            var raced = tx.execute(status -> orders.findByCustomerIdAndIdempotencyKey(customer.id(), idempotencyKey)
                    .map(this::detail));
            if (raced != null && raced.isPresent()) {
                return new Placed(raced.get(), false);
            }
            throw e;
        }
    }

    private OrderViews.Detail create(UUID customerId, String key, OrderRequests.Place req, ClientContext client, String userAgent) {
        OrderableShop shop = shops.shop(req.vendorId())
                .orElseThrow(() -> BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán."));
        if (!shop.open()) {
            throw BusinessException.conflict("SHOP_CLOSED", "Quán đang đóng cửa hoặc tạm ngưng nhận đơn.");
        }
        if (shop.ownerUserId().equals(customerId)) {
            throw BusinessException.conflict("CANNOT_ORDER_OWN_SHOP", "Bạn không thể đặt món từ quán của chính mình.");
        }
        int maxOpen = settings.getInt(MAX_OPEN_ORDERS_KEY, 3);
        if (orders.countByCustomerIdAndStatusIn(customerId, OrderStatus.OPEN) >= maxOpen) {
            throw BusinessException.conflict("TOO_MANY_OPEN_ORDERS",
                    "Bạn đang có " + maxOpen + " đơn chưa hoàn tất. Hãy đợi các đơn đó xong rồi đặt thêm.");
        }
        DeliveryAddresses.Snapshot address = addresses.snapshotFor(customerId, req.addressId())
                .orElseThrow(() -> BusinessException.notFound("ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ giao hàng."));
        double distanceKm = GeoDistance.haversineKm(address.lat(), address.lng(), shop.lat(), shop.lng());
        if (distanceKm > shop.radiusKm()) {
            throw BusinessException.conflict("OUT_OF_DELIVERY_RADIUS", "Địa chỉ của bạn nằm ngoài vùng giao hàng của quán.");
        }

        Map<UUID, OrderableDish> dishes = shops.dishes(shop.id(),
                req.items().stream().map(OrderRequests.Line::menuItemId).collect(Collectors.toSet()));
        boolean online = "ONLINE".equals(req.paymentMethod());
        Order order = new Order(customerId, shop.id(), shop.name(), online ? OrderStatus.PENDING_PAYMENT : OrderStatus.PLACED, req.paymentMethod(), address.recipientName(),
                address.recipientPhone(), address.address(), address.lat(), address.lng(), blankToNull(req.note()), key,
                client.ip(), truncate(userAgent, 300));

        Map<UUID, Integer> quantityByDish = new LinkedHashMap<>();
        int itemsTotal = 0;
        int commission = 0;
        for (OrderRequests.Line line : req.items()) {
            OrderableDish dish = dishes.get(line.menuItemId());
            if (dish == null || !dish.orderable()) {
                throw itemProblem(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE", "Một món trong giỏ hiện không còn bán.", line.menuItemId());
            }
            List<OrderableOption> chosen = chooseOptions(dish, line);
            int unitPrice = dish.price() + chosen.stream().mapToInt(OrderableOption::priceDelta).sum();
            BigDecimal rate = categories.effectiveCommissionRate(dish.categoryId())
                    .orElseGet(() -> new BigDecimal(settings.getString(DEFAULT_COMMISSION_KEY, "10")));
            int lineTotal = unitPrice * line.quantity();
            int lineCommission = percentOf(lineTotal, rate);
            OrderItem item = new OrderItem(dish.id(), dish.name(), unitPrice, line.quantity(), blankToNull(line.note()),
                    dish.categoryId(), rate, lineCommission);
            for (OrderableGroup group : dish.groups()) {
                for (OrderableOption option : chosen) {
                    if (group.options().contains(option)) {
                        item.addOption(group.name(), option.name(), option.priceDelta());
                    }
                }
            }
            order.addItem(item);
            quantityByDish.merge(dish.id(), line.quantity(), Integer::sum);
            itemsTotal += lineTotal;
            commission += lineCommission;
        }

        if (shop.minOrderValue() != null && itemsTotal < shop.minOrderValue()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BELOW_MIN_ORDER",
                    "Đơn tối thiểu của quán là " + shop.minOrderValue() + " ₫.").withProperty("minOrderValue", shop.minOrderValue());
        }
        for (Map.Entry<UUID, Integer> e : quantityByDish.entrySet()) {
            if (!shops.takeStock(e.getKey(), e.getValue())) {
                throw itemProblem(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Một món không còn đủ số phần bạn chọn.", e.getKey());
            }
        }
        int deliveryFee = shop.freeDeliveryThreshold() != null && itemsTotal >= shop.freeDeliveryThreshold() ? 0 : shop.deliveryFee();
        order.setTotals(itemsTotal, 0, deliveryFee, itemsTotal + deliveryFee, commission);
        if (online && (order.getGrandTotal() < MIN_ONLINE || order.getGrandTotal() > MAX_ONLINE)) {
            // MoMo's payment API only accepts this range; the customer can pay the same order at the door instead.
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ONLINE_AMOUNT_OUT_OF_RANGE",
                    "Thanh toán online chỉ áp dụng cho đơn từ 1.000 ₫ đến 50.000.000 ₫. Hãy chọn thanh toán khi nhận hàng.");
        }

        orders.saveAndFlush(order);
        history.save(new OrderStatusHistory(order.getId(), null, order.getStatus(), ActorType.CUSTOMER, customerId, null));
        if (!online) {
            payments.recordCashOnDelivery(order.getId(), order.getGrandTotal());
        }
        transitions.placed(order);
        return detail(order);
    }

    /** Resolves the chosen option ids against the dish's groups and enforces each group's min and max. */
    private static List<OrderableOption> chooseOptions(OrderableDish dish, OrderRequests.Line line) {
        List<UUID> ids = line.optionIds() == null ? List.of() : line.optionIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw itemProblem(HttpStatus.BAD_REQUEST, "OPTION_DUPLICATED", "Mỗi lựa chọn chỉ được chọn một lần.", dish.id());
        }
        Map<UUID, OrderableGroup> groupOf = new LinkedHashMap<>();
        Map<UUID, OrderableOption> optionById = new LinkedHashMap<>();
        for (OrderableGroup g : dish.groups()) {
            for (OrderableOption o : g.options()) {
                groupOf.put(o.id(), g);
                optionById.put(o.id(), o);
            }
        }
        Map<UUID, Integer> perGroup = new LinkedHashMap<>();
        List<OrderableOption> chosen = new ArrayList<>();
        for (UUID id : ids) {
            OrderableOption option = optionById.get(id);
            if (option == null) {
                throw itemProblem(HttpStatus.BAD_REQUEST, "OPTION_NOT_OFFERED", "Lựa chọn này không thuộc món đã chọn.", dish.id());
            }
            if (!option.available()) {
                throw itemProblem(HttpStatus.CONFLICT, "OPTION_UNAVAILABLE", "Lựa chọn “" + option.name() + "” hiện đã hết.", dish.id());
            }
            perGroup.merge(groupOf.get(id).id(), 1, Integer::sum);
            chosen.add(option);
        }
        for (OrderableGroup g : dish.groups()) {
            int count = perGroup.getOrDefault(g.id(), 0);
            if (count < g.min() || count > g.max()) {
                throw itemProblem(HttpStatus.BAD_REQUEST, "OPTION_COUNT_INVALID",
                        "Nhóm “" + g.name() + "” cần chọn " + (g.min() == g.max() ? g.max() : g.min() + "–" + g.max()) + " lựa chọn.",
                        dish.id());
            }
        }
        return chosen;
    }

    // --- changing

    /** Free cancellation through CONFIRMED; once the shop starts preparing it is too late (cancel-order.md). */
    @Transactional
    public OrderViews.Detail cancel(UUID customerId, UUID orderId, String reason) {
        return move(customerId, orderId, OrderStatus.CANCELLED, reason);
    }

    /** The customer has the food in hand (confirm-order-received.md); the shop can also mark it, first one wins. */
    @Transactional
    public OrderViews.Detail received(UUID customerId, UUID orderId) {
        return move(customerId, orderId, OrderStatus.DELIVERED, null);
    }

    private OrderViews.Detail move(UUID customerId, UUID orderId, OrderStatus to, String reason) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        transitions.move(order, to, ActorType.CUSTOMER, customerId, reason);
        return detail(orders.findById(orderId).orElseThrow());
    }

    /** Calls MoMo outside any transaction, then reads the order again with the attempt that now exists. */
    private OrderViews.Detail startPayment(UUID orderId, long number, int amount, Instant placedAt) {
        payments.startOnline(orderId, number, amount, placedAt.plus(orderSettings.paymentWindow()));
        return tx.execute(status -> orders.findById(orderId).map(this::detail).orElseThrow());
    }

    /** A fresh MoMo attempt for an online order that is still unpaid (the first one failed, lapsed or was abandoned). */
    public OrderViews.Detail pay(UUID customerId, UUID orderId) {
        Order order = tx.execute(status -> orders.findByIdAndCustomerId(orderId, customerId).orElse(null));
        if (order == null) {
            throw BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT || !"ONLINE".equals(order.getPaymentMethod())) {
            throw BusinessException.conflict("PAYMENT_NOT_PENDING", "Đơn này không còn chờ thanh toán.");
        }
        Instant deadline = order.getPlacedAt().plus(orderSettings.paymentWindow());
        if (!Instant.now().isBefore(deadline)) {
            throw BusinessException.conflict("PAYMENT_EXPIRED", "Đã hết thời hạn thanh toán của đơn này.");
        }
        return startPayment(order.getId(), order.getNumber(), order.getGrandTotal(), order.getPlacedAt());
    }

    // --- reading

    @Transactional(readOnly = true)
    public OrderViews.Detail get(UUID customerId, UUID orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId).map(this::detail)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
    }

    /** Newest first; {@code statuses} empty means all. */
    @Transactional(readOnly = true)
    public OrderViews.Page list(UUID customerId, Collection<OrderStatus> statuses, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        PageRequest paging = PageRequest.of(page, size);
        var result = statuses == null || statuses.isEmpty() ? orders.findForCustomer(customerId, paging)
                : orders.findForCustomerWithStatus(customerId, statuses, paging);
        List<UUID> ids = result.getContent().stream().map(Order::getId).toList();
        Map<UUID, List<String>> lines = new LinkedHashMap<>();
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : orders.linePreviews(ids)) {
                UUID id = (UUID) row[0];
                lines.computeIfAbsent(id, k -> new ArrayList<>()).add(row[2] + "× " + row[1]);
                counts.merge(id, (Integer) row[2], Integer::sum);
            }
        }
        List<OrderViews.Summary> items = result.getContent().stream().map(o -> new OrderViews.Summary(o.getId(), o.getNumber(),
                o.getStatus(), o.getVendorName(), o.getGrandTotal(), counts.getOrDefault(o.getId(), 0),
                String.join(", ", lines.getOrDefault(o.getId(), List.of())), o.getPlacedAt())).toList();
        return new OrderViews.Page(items, page, size, result.getTotalElements());
    }

    OrderViews.Detail detail(Order o) {
        List<OrderViews.Line> lines = o.getItems().stream().map(i -> new OrderViews.Line(i.getMenuItemId(), i.getName(),
                i.getQuantity(), i.getUnitPrice(), i.getLineTotal(), i.getNote(),
                i.getOptions().stream().map(op -> new OrderViews.Option(op.getGroupName(), op.getOptionName(), op.getPriceDelta())).toList()))
                .toList();
        List<OrderViews.Step> timeline = history.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream().map(h -> new OrderViews.Step(
                h.getFromStatus(), h.getToStatus(), actor(h.getActedByType()), h.getReason(), h.getCreatedAt())).toList();
        return new OrderViews.Detail(o.getId(), o.getNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(),
                new OrderViews.Shop(o.getVendorId(), o.getVendorName()),
                new OrderViews.Delivery(o.getDeliveryName(), o.getDeliveryPhone(), o.getDeliveryAddress(), o.getNote()), lines,
                new OrderViews.Totals(o.getItemsTotal(), o.getDiscount(), o.getDeliveryFee(), o.getGrandTotal()), o.getPlacedAt(),
                timeline, paymentOf(o), reviews.findByOrderId(o.getId()).map(r -> new OrderViews.Reviewed(r.getId(), r.getRating(), r.isHidden())).orElse(null));
    }

    private OrderViews.Payment paymentOf(Order o) {
        if (o.getStatus() != OrderStatus.PENDING_PAYMENT || !"ONLINE".equals(o.getPaymentMethod())) {
            return null;
        }
        return payments.currentAttempt(o.getId())
                .map(a -> new OrderViews.Payment(a.number(), a.status(), a.payUrl(), a.deeplink(), a.qrCodeUrl(), a.expiresAt()))
                .orElse(null);
    }

    /** The customer sees "the shop", never the individual staff member who pressed the button. */
    private static String actor(ActorType type) {
        return switch (type) {
            case CUSTOMER -> "CUSTOMER";
            case SHOP_ACCOUNT, MAIN_ACCOUNT, MEMBER -> "SHOP";
            case ADMIN -> "ADMIN";
            case SYSTEM -> "SYSTEM";
        };
    }

    // --- helpers

    private static int percentOf(int amount, BigDecimal ratePercent) {
        return BigDecimal.valueOf(amount).multiply(ratePercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValueExact();
    }

    private static BusinessException itemProblem(HttpStatus status, String code, String message, UUID menuItemId) {
        return new BusinessException(status, code, message).withProperty("menuItemId", menuItemId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
