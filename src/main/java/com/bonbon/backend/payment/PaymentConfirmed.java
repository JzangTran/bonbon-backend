package com.bonbon.backend.payment;

import java.util.UUID;

/**
 * Published in the transaction that records a successful online payment. The order module listens: it moves an unpaid
 * order to placed, or asks for the money back when the order is already closed (a late success).
 */
public record PaymentConfirmed(UUID orderId, UUID paymentId, int amount) {
}
