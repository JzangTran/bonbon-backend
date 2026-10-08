package com.bonbon.backend.shopperformance;

import java.util.UUID;

/**
 * A case was settled: {@code outcome} is UPHELD (the customer is refunded and the shop bears it) or DISMISSED;
 * {@code decidedBy} is SHOP (it accepted), ADMIN or SYSTEM.
 */
public record OrderCaseDecided(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, String outcome, String decidedBy, int refundAmount,
        String reason) {
}
