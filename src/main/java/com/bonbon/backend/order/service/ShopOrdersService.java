package com.bonbon.backend.order.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.repository.OrderRepository;
import com.bonbon.backend.order.repository.OrderStatusHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A shop reading and moving its own orders (confirm-order.md, update-order-status.md, view-order-detail.md). */
@Service
class ShopOrdersService implements ShopOrders {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_PAGE_SIZE = 50;
    /** Unpaid orders are not real orders yet, so a shop never sees them. */
    private static final EnumSet<OrderStatus> VISIBLE = EnumSet.complementOf(EnumSet.of(OrderStatus.PENDING_PAYMENT));

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final OrderTransitions transitions;
    private final OrderSettings timers;
    private final SystemSettingsService settings;

    ShopOrdersService(OrderRepository orders, OrderStatusHistoryRepository history, OrderTransitions transitions,
            OrderSettings timers, SystemSettingsService settings) {
        this.orders = orders;
        this.history = history;
        this.transitions = transitions;
        this.timers = timers;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public ShopOrderPage list(UUID vendorId, Collection<OrderStatus> statuses, LocalDate from, LocalDate to, boolean oldestFirst,
            int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        EnumSet<OrderStatus> wanted = statuses == null || statuses.isEmpty() ? EnumSet.copyOf(VISIBLE) : EnumSet.copyOf(statuses);
        wanted.retainAll(VISIBLE);
        if (wanted.isEmpty()) {
            return new ShopOrderPage(List.of(), page, size, 0);
        }
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(VIETNAM).toInstant();
        Instant end = to == null ? Instant.parse("2999-01-01T00:00:00Z") : to.plusDays(1).atStartOfDay(VIETNAM).toInstant();
        Sort sort = oldestFirst ? Sort.by("placedAt").ascending() : Sort.by("placedAt").descending();
        var result = orders.findForVendor(vendorId, wanted, start, end, PageRequest.of(page, size, sort));

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
        List<ShopOrderSummary> items = result.getContent().stream().map(o -> new ShopOrderSummary(o.getId(), o.getNumber(), o.getStatus(),
                o.getDeliveryName(), o.getGrandTotal(), counts.getOrDefault(o.getId(), 0),
                String.join(", ", lines.getOrDefault(o.getId(), List.of())), o.getPlacedAt(), responseDeadline(o), handoverDeadline(o))).toList();
        return new ShopOrderPage(items, page, size, result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public ShopOrderDetail get(UUID vendorId, UUID orderId) {
        return detail(find(vendorId, orderId));
    }

    @Override
    @Transactional
    public ShopOrderDetail transition(UUID vendorId, UUID orderId, OrderStatus to, String reason, ActorType actorType, UUID actorId) {
        Order order = find(vendorId, orderId);
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            throw BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng.");
        }
        transitions.move(order, to, actorType, actorId, reason);
        return detail(orders.findById(orderId).orElseThrow());
    }

    // --- internals

    private Order find(UUID vendorId, UUID orderId) {
        return orders.findByIdAndVendorId(orderId, vendorId)
                .filter(o -> o.getStatus() != OrderStatus.PENDING_PAYMENT)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
    }

    /** A new order auto-rejects if the shop stays silent; the countdown is shown while it waits. */
    private Instant responseDeadline(Order o) {
        return o.getStatus() == OrderStatus.PLACED ? o.getPlacedAt().plus(timers.sellerResponse()) : null;
    }

    /** From confirmation until it leaves the kitchen. */
    private Instant handoverDeadline(Order o) {
        boolean waiting = o.getStatus() == OrderStatus.CONFIRMED || o.getStatus() == OrderStatus.PREPARING;
        return waiting && o.getConfirmedAt() != null ? o.getConfirmedAt().plus(timers.handover()) : null;
    }

    private ShopOrderDetail detail(Order o) {
        // Masked once finished and the report window has passed, unless a case is still open (view-order-detail.md).
        Duration window = Duration.ofHours(settings.getLong("incident.report_window_hours", 24));
        boolean masked = o.getStatus().isTerminal() && !o.isIncidentHold() && o.getFinishedAt() != null
                && o.getFinishedAt().plus(window).isBefore(Instant.now());
        List<Line> lines = o.getItems().stream().map(i -> new Line(i.getName(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal(), i.getNote(),
                i.getOptions().stream().map(op -> new LineOption(op.getGroupName(), op.getOptionName(), op.getPriceDelta())).toList())).toList();
        List<Step> timeline = history.findByOrderIdOrderByCreatedAtAsc(o.getId()).stream().map(h -> new Step(h.getFromStatus(), h.getToStatus(),
                switch (h.getActedByType()) {
                    case CUSTOMER -> "CUSTOMER";
                    case SHOP_ACCOUNT, MAIN_ACCOUNT, MEMBER -> "SHOP";
                    case ADMIN -> "ADMIN";
                    case SYSTEM -> "SYSTEM";
                }, h.getReason(), h.getCreatedAt())).toList();
        return new ShopOrderDetail(o.getId(), o.getNumber(), o.getStatus(), o.getPaymentMethod(), o.getPaymentStatus(), o.getDeliveryName(),
                masked ? maskPhone(o.getDeliveryPhone()) : o.getDeliveryPhone(),
                masked ? "Địa chỉ đã được ẩn sau khi đơn hoàn tất." : o.getDeliveryAddress(), o.getNote(), masked, lines,
                new Totals(o.getItemsTotal(), o.getDiscount(), o.getDeliveryFee(), o.getGrandTotal()), o.getPlacedAt(), responseDeadline(o),
                handoverDeadline(o), timeline);
    }

    /** 0901234678 becomes 090xxxx678. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 6) {
            return "xxx";
        }
        return phone.substring(0, 3) + "x".repeat(phone.length() - 6) + phone.substring(phone.length() - 3);
    }
}
