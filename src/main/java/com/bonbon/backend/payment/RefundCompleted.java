package com.bonbon.backend.payment;

import java.util.UUID;

/** The customer has their money back; {@code mode} says how (GATEWAY through MoMo, MANUAL by a bank transfer). */
public record RefundCompleted(UUID orderId, long orderNumber, UUID customerId, int amount, String mode) {
}
