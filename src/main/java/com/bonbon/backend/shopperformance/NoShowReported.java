package com.bonbon.backend.shopperformance;

import java.time.Instant;
import java.util.UUID;

/** A shop reported that the customer was not there; the customer must answer before {@code answerDueAt}. */
public record NoShowReported(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, Instant answerDueAt) {
}
