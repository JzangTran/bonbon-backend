package com.bonbon.backend.shopperformance;

import java.util.UUID;

/** An administrator reopened a decided case to look at it once more; both sides are told. */
public record OrderCaseReopened(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, String reason) {
}
