package com.bonbon.backend.payment;

import java.util.UUID;

/** A refund can only go back by bank transfer and the customer has to say to which account. */
public record RefundNeedsDestination(UUID orderId, long orderNumber, UUID customerId, int amount) {
}
