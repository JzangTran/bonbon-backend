package com.bonbon.backend.shopperformance;

import java.util.UUID;

/**
 * An administrator changed a shop's points. {@code kind} is WAIVED, ADDED, APPEAL_ACCEPTED (the point was waived) or
 * APPEAL_REJECTED; {@code activePoints} is what the shop has afterwards and {@code reason} is what the administrator wrote.
 */
public record ShopPenaltyDecided(UUID vendorId, UUID penaltyId, String kind, int activePoints, String reason) {
}
