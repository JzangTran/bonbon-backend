package com.bonbon.backend.marketplace.merchant;

import java.util.UUID;

/** A shop started closing (the owner asked) or finished closing (nothing was left to settle). {@code status} is CLOSING or CLOSED. */
public record ShopClosureChanged(UUID vendorId, UUID ownerUserId, String shopName, String status, String reason) {
}
