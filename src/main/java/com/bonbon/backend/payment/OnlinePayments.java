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
    void recordCashOnDelivery(UUID orderId, long orderNumber, UUID customerId, int amount);

    /** The shop handed the food over and the cash changed hands: the payment is done. */
    void cashCollected(UUID orderId);

    /**
     * The order was cancelled or rejected: an unpaid payment fails and its open attempts expire; a payment that was
     * made is queued for a refund. Runs in the order's transaction.
     */
    void orderClosed(UUID orderId);

    /**
     * Starts a MoMo payment for the order, creating the payment row the first time and a fresh attempt every time.
     * Calls the gateway (up to 30 s), so it must run outside any transaction. A gateway failure is not thrown: the
     * attempt comes back {@code FAILED} and the customer can try again.
     */
    Attempt startOnline(UUID orderId, long orderNumber, UUID customerId, int amount, Instant expiresAt);

    /** The newest attempt of the order's online payment, if there is one. */
    Optional<Attempt> currentAttempt(UUID orderId);

    /**
     * An order case was upheld: gives {@code amount} back to the customer, once per case. Online payments go back through
     * MoMo; cash orders and amounts MoMo cannot take go to the manual bank-transfer queue and the customer is asked for an
     * account. Empty when nothing could be queued (no successful payment, or more than was paid). Runs in the caller's transaction.
     */
    Optional<UUID> refundForCase(UUID orderId, UUID caseId, int amount);

    /** Money arrived for an order that can no longer use it: queue the refund (idempotent per payment). */
    void requestLateRefund(UUID paymentId);

    /**
     * The order's refund, if money is owed or was given back. {@code status} is REQUESTED, PROCESSING, NEEDS_DESTINATION
     * (the customer must give a bank account), COMPLETED or FAILED; {@code mode} GATEWAY (back through MoMo) or MANUAL
     * (a bank transfer by an admin).
     */
    Optional<RefundSummary> refundOf(UUID orderId);

    record RefundSummary(String status, String mode, int amount, String failureReason, String destinationLast4) {

        public boolean needsDestination() {
            return "NEEDS_DESTINATION".equals(status);
        }
    }

    /** {@code status} is PENDING (waiting for the customer), SUCCESS, FAILED or EXPIRED. */
    record Attempt(int number, String status, String payUrl, String deeplink, String qrCodeUrl, Instant expiresAt) {
    }
}
