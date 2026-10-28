package com.bonbon.backend.marketplace.category;

import java.time.Instant;
import java.util.UUID;

/**
 * Something happened to a dish category case that the shop must hear about. {@code kind} is FLAGGED, AUTO_CORRECTED, APPEAL_ACCEPTED,
 * APPEAL_REJECTED or RECLASSIFIED; {@code categoryName} is the category the dish is in, or must move to, as the kind says;
 * {@code until} is the fix deadline (FLAGGED) or the decision date (AUTO_CORRECTED).
 */
public record DishCategoryCaseChanged(UUID caseId, UUID vendorId, UUID dishId, String dishName, String kind, String categoryName, Instant until, String reason) {
}
