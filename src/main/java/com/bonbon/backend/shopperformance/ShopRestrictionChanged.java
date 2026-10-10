package com.bonbon.backend.shopperformance;

import java.time.Instant;
import java.util.UUID;

/**
 * What penalty points are doing to a shop's visibility. {@code status} is SCHEDULED (the notice: the restriction starts at
 * {@code startsAt}, never sooner than 5 days after this), APPLIED, LIFTED (points fell back) or CANCELLED (they fell before it began).
 */
public record ShopRestrictionChanged(UUID vendorId, String status, Instant startsAt, int activePoints) {
}
