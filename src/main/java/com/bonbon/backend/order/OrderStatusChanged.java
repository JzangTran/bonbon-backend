package com.bonbon.backend.order;

import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/**
 * Published inside the transaction of every order change, including the first one (placed: {@code from} is null).
 * Listeners that react outside the database (live updates, notifications) use {@code @ApplicationModuleListener}
 * so they run after commit.
 */
/** {@code by} says who did it, so the other side can be told (the customer's own cancel is not news to the customer). */
public record OrderStatusChanged(UUID orderId, long number, UUID customerId, UUID vendorId, OrderStatus from, OrderStatus to,
        ActorType by) {
}
