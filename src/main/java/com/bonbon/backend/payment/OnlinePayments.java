package com.bonbon.backend.payment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the order module needs from payments: record cash on delivery, start a MoMo payment attempt, read the current
 * attempt, and keep the payment row in step with the order. Callers are the order transitions and the place and pay
 * requests; everything about MoMo itself stays inside this module.
 */
public interface OnlinePayments {

    /** The payments row of a cash-on-delivery order, created with the order. */
    void recordCashOnDelivery(UUID orderId, int amount);

    /** The shop handed the food over and the cash changed hands: the payment is done. */
    void cashCollected(UUID orderId);

    /** The order ended without being paid for (or without being delivered): an unpaid payment fails, open attempts expire. */
    void orderClosed(UUID orderId);

    /**
     * Starts a MoMo payment for the order, creating the payment row the first time and a fresh attempt every time.
     * Calls the gateway (up to 30 s), so it must run outside any transaction. A gateway failure is not thrown: the
     * attempt comes back {@code FAILED} and the customer can try again.
     */
    Attempt startOnline(UUID orderId, long orderNumber, int amount, Instant expiresAt);

    /** The newest attempt of the order's online payment, if there is one. */
    Optional<Attempt> currentAttempt(UUID orderId);

    /** Money arrived for an order that can no longer use it: queue the refund (idempotent per payment). */
    void requestLateRefund(UUID paymentId);

    /** {@code status} is PENDING (waiting for the customer), SUCCESS, FAILED or EXPIRED. */
    record Attempt(int number, String status, String payUrl, String deeplink, String qrCodeUrl, Instant expiresAt) {
    }
}
