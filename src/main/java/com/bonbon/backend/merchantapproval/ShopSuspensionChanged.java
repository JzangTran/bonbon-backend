package com.bonbon.backend.merchantapproval;

import java.time.Instant;
import java.util.UUID;

/**
 * A shop's suspension moved on. {@code status} is SCHEDULED (the notice: it takes effect at {@code effectiveAt}, never sooner than 5 days
 * after this unless a competent authority asked), APPLIED (the shop is now suspended), CANCELLED (called off during the notice) or
 * LIFTED (an administrator reinstated the shop). {@code reason} is what the administrator wrote.
 */
public record ShopSuspensionChanged(UUID vendorId, String status, String reason, Instant effectiveAt) {
}
