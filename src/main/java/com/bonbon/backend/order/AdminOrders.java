package com.bonbon.backend.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only views of any order for the administrators' lookup (flows/support/admin-order-lookup.md). Nothing here is
 * masked: the caller decides what an administrator may see. Money is integer VND.
 */
public interface AdminOrders {

    Optional<AdminOrder> byNumber(long number);

    Optional<AdminOrder> get(UUID orderId);

    /** The customer's newest orders first, at most {@code limit}. */
    List<AdminOrderLine> ofCustomer(UUID customerId, int limit);

    /** One row of a list of orders. */
    record AdminOrderLine(UUID id, long number, OrderStatus status, String shopName, int grandTotal, Instant placedAt) {
    }

    record AdminOrder(UUID id, long number, OrderStatus status, UUID vendorId, String shopName, UUID customerId, String deliveryName, String deliveryPhone,
            String deliveryAddress, String note, String paymentMethod, String paymentStatus, List<ShopOrders.Line> items, int itemsTotal, int discount,
            int deliveryFee, int grandTotal, int commissionAmount, Instant placedAt, Instant confirmedAt, Instant outForDeliveryAt, Instant finishedAt,
            List<ShopOrders.Step> timeline) {
    }
}
