package com.bonbon.backend.merchant;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The rating kept on each shop so browsing never aggregates reviews. The order module owns the reviews and
 * tells the shop what they add up to.
 */
public interface ShopRatings {

    /**
     * Locks the shop's rating, asks {@code totals} for what the visible reviews now add up to and stores it. Taking
     * the lock before reading is what stops two reviews written at the same time from overwriting each other.
     * Runs in the caller's transaction.
     */
    void refresh(UUID vendorId, Supplier<RatingTotals> totals);

    record RatingTotals(int sum, int count) {
    }
}
