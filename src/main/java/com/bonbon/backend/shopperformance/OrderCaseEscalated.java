package com.bonbon.backend.shopperformance;

import java.util.UUID;

/** The shop disputed a case or let its time run out: an administrator decides now. {@code why} is DISPUTED or NO_RESPONSE. */
public record OrderCaseEscalated(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, String why) {
}
