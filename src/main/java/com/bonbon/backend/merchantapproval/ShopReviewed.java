package com.bonbon.backend.merchantapproval;

import java.util.UUID;

/** Published when an administrator approves or rejects a shop; {@code reason} is null for an approval. */
public record ShopReviewed(UUID vendorId, UUID ownerUserId, String shopName, boolean approved, String reason) {
}
