package com.bonbon.backend.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/**
 * A shop working its orders (order-fulfillment). Every call is scoped to one shop: an order of another shop is
 * simply not found. The caller has already proved it acts for {@code vendorId}.
 */
public interface ShopOrders {

    /** Newest first, or oldest first when {@code oldestFirst}; {@code statuses} empty means every status except unpaid ones. */
    ShopOrderPage list(UUID vendorId, Collection<OrderStatus> statuses, LocalDate from, LocalDate to, boolean oldestFirst,
            int page, int size);

    ShopOrderDetail get(UUID vendorId, UUID orderId);

    /**
     * Moves the order one step ({@code to}). The shop may confirm or reject a new order, advance a confirmed one
     * to preparing and out for delivery, mark it delivered, and cancel after confirming. {@code reason} is
     * required for a reject or a cancel.
     */
    ShopOrderDetail transition(UUID vendorId, UUID orderId, OrderStatus to, String reason, ActorType actorType, UUID actorId);

    /**
     * {@code phone} and {@code address} are masked once the order has finished and the report window has passed
     * (view-order-detail.md). {@code responseDeadline} counts down while the order is new; {@code handoverDeadline}
     * from the moment it is confirmed until it leaves the kitchen.
     */
    record ShopOrderDetail(UUID id, long number, OrderStatus status, String paymentMethod, String paymentStatus,
            String customerName, String customerPhone, String deliveryAddress, String note, boolean contactMasked,
            List<Line> items, Totals totals, Instant placedAt, Instant responseDeadline, Instant handoverDeadline,
            List<Step> timeline) {
    }

    record Line(String name, int quantity, int unitPrice, int lineTotal, String note, List<LineOption> options) {
    }

    record LineOption(String group, String name, int priceDelta) {
    }

    record Totals(int itemsTotal, int discount, int deliveryFee, int grandTotal) {
    }

    /** {@code by} is CUSTOMER, SHOP, SYSTEM or ADMIN. */
    record Step(OrderStatus from, OrderStatus to, String by, String reason, Instant at) {
    }

    record ShopOrderSummary(UUID id, long number, OrderStatus status, String customerName, int grandTotal, int itemCount,
            String itemsPreview, Instant placedAt, Instant responseDeadline, Instant handoverDeadline) {
    }

    record ShopOrderPage(List<ShopOrderSummary> items, int page, int size, long total) {
    }
}
