package com.bonbon.backend.order;

import java.util.UUID;

/**
 * Published inside the transaction of every order change, including the first one (placed: {@code from} is null).
 * Listeners that react outside the database (live updates, notifications) use {@code @ApplicationModuleListener}
 * so they run after commit.
 */
public record OrderStatusChanged(UUID orderId, long number, UUID customerId, UUID vendorId, OrderStatus from, OrderStatus to) {
}
