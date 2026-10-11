package com.bonbon.backend.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What an administrator looking up an order sees of its payment (flows/support/admin-order-lookup.md). */
public interface PaymentLookup {

    Optional<PaymentSummary> ofOrder(UUID orderId);

    record PaymentSummary(String method, String provider, String status, int amount, int refundedAmount, List<PaymentRefund> refunds) {
    }

    /** {@code mode} is GATEWAY (back to MoMo) or MANUAL (bank transfer by hand). */
    record PaymentRefund(String reason, int amount, String status, String mode, Instant requestedAt) {
    }
}
