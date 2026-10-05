package com.bonbon.backend.order.service;

import static com.bonbon.backend.order.OrderStatus.CANCELLED;
import static com.bonbon.backend.order.OrderStatus.CONFIRMED;
import static com.bonbon.backend.order.OrderStatus.DELIVERED;
import static com.bonbon.backend.order.OrderStatus.OUT_FOR_DELIVERY;
import static com.bonbon.backend.order.OrderStatus.PLACED;
import static com.bonbon.backend.order.OrderStatus.PREPARING;
import static com.bonbon.backend.order.OrderStatus.REJECTED;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.entity.OrderItem;
import com.bonbon.backend.order.entity.OrderStatusHistory;
import com.bonbon.backend.order.repository.OrderRepository;
import com.bonbon.backend.order.repository.OrderStatusHistoryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The one place an order changes status (flows/order/README.md). Every change is a conditional update ("only if it
 * is still in the status I read"), a history row and an {@link OrderStatusChanged} event, all in the caller's
 * transaction. The side effects of a terminal status (stock back, COD paid) happen here too.
 */
@Component
class OrderTransitions {

    /** Who may do what, one step at a time; any other move is rejected. */
    enum Actor {
        CUSTOMER, SHOP, SYSTEM;

        static Actor of(ActorType type) {
            return switch (type) {
                case CUSTOMER -> CUSTOMER;
                case SHOP_ACCOUNT, MAIN_ACCOUNT, MEMBER -> SHOP;
                case SYSTEM, ADMIN -> SYSTEM;
            };
        }
    }

    private static final Map<Actor, Map<OrderStatus, Set<OrderStatus>>> ALLOWED = new EnumMap<>(Actor.class);

    static {
        ALLOWED.put(Actor.CUSTOMER, Map.of(
                PLACED, Set.of(CANCELLED),
                CONFIRMED, Set.of(CANCELLED),
                OUT_FOR_DELIVERY, Set.of(DELIVERED)));
        ALLOWED.put(Actor.SHOP, Map.of(
                PLACED, Set.of(CONFIRMED, REJECTED),
                CONFIRMED, Set.of(PREPARING, CANCELLED),
                PREPARING, Set.of(OUT_FOR_DELIVERY, CANCELLED),
                OUT_FOR_DELIVERY, Set.of(DELIVERED)));
        ALLOWED.put(Actor.SYSTEM, Map.of(
                PLACED, Set.of(REJECTED),
                CONFIRMED, Set.of(CANCELLED),
                PREPARING, Set.of(CANCELLED),
                OUT_FOR_DELIVERY, Set.of(DELIVERED)));
    }

    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final ShopOrdering shops;
    private final ApplicationEventPublisher events;

    OrderTransitions(OrderRepository orders, OrderStatusHistoryRepository history, ShopOrdering shops, ApplicationEventPublisher events) {
        this.orders = orders;
        this.history = history;
        this.shops = shops;
        this.events = events;
    }

    /** Publishes the "placed" event for a brand-new order. */
    void placed(Order order) {
        events.publishEvent(new OrderStatusChanged(order.getId(), order.getNumber(), order.getCustomerId(), order.getVendorId(), null,
                order.getStatus(), ActorType.CUSTOMER));
    }

    /**
     * Moves {@code order} (just read by the caller, inside the caller's transaction) to {@code to}. Fails with
     * {@code ORDER_ALREADY_CHANGED} when somebody else got there first, {@code INVALID_TRANSITION} when the move is
     * not allowed from the current status, and {@code REASON_REQUIRED} when a reject or cancel by the shop has none.
     */
    void move(Order order, OrderStatus to, ActorType actorType, UUID actorId, String reason) {
        Actor actor = Actor.of(actorType);
        OrderStatus from = order.getStatus();
        if (from == to) {
            throw alreadyChanged();
        }
        if (!ALLOWED.get(actor).getOrDefault(from, Set.of()).contains(to)) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "Đơn đang ở trạng thái không thể chuyển sang bước này.")
                    .withProperty("status", from.name());
        }
        String cleanReason = reason == null || reason.isBlank() ? null : reason.strip();
        if (actor == Actor.SHOP && (to == REJECTED || to == CANCELLED) && cleanReason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Vui lòng nhập lý do.");
        }
        // Read what the side effects need now: the update below clears the persistence context.
        Map<UUID, Integer> toReturn = new java.util.LinkedHashMap<>();
        if (to == REJECTED || to == CANCELLED) {
            for (OrderItem item : order.getItems()) {
                toReturn.merge(item.getMenuItemId(), item.getQuantity(), Integer::sum);
            }
        }
        Instant now = Instant.now();
        String paymentStatus = null;
        if (to == DELIVERED && "COD".equals(order.getPaymentMethod()) && !order.isIncidentHold()) {
            paymentStatus = "PAID";
        }
        int changed = orders.transition(order.getId(), from, to, now, to == CONFIRMED ? now : null,
                to == OUT_FOR_DELIVERY ? now : null, to.isTerminal() ? now : null, paymentStatus);
        if (changed == 0) {
            throw alreadyChanged();
        }
        history.save(new OrderStatusHistory(order.getId(), from, to, actorType, actorId, cleanReason));
        toReturn.forEach(shops::returnStock);
        events.publishEvent(new OrderStatusChanged(order.getId(), order.getNumber(), order.getCustomerId(), order.getVendorId(), from, to, actorType));
    }

    private static BusinessException alreadyChanged() {
        return new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_CHANGED", "Đơn hàng vừa được cập nhật. Hãy tải lại để xem trạng thái mới.");
    }
}
