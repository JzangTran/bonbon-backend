package com.bonbon.backend.shopperformance;

import java.time.Instant;
import java.util.UUID;

/** A customer filed a case about a delivered order: the shop is asked to answer before {@code responseDueAt}. */
public record OrderCaseOpened(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, String type, int refundAmount,
        Instant responseDueAt) {
}
