package com.bonbon.backend.merchant;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How unpaid commission affects a shop (flows/settlement/collect-commission-debt.md). Settlement decides the stage
 * from the ledger and writes it here; this module applies the consequences: a restricted shop drops out of search and
 * sorts last, a paused one takes no new orders and cannot switch intake back on.
 */
public interface ShopCommissionStanding {

    /** In order of severity. {@code REVIEW} also keeps the shop paused; an administrator decides what happens next. */
    enum Stage {
        NONE, OVERDUE, RESTRICTED, PAUSED, REVIEW;

        public boolean restrictsVisibility() {
            return compareTo(RESTRICTED) >= 0;
        }

        public boolean pausesOrders() {
            return compareTo(PAUSED) >= 0;
        }
    }

    record Standing(Stage stage, Instant overdueSince) {
    }

    /** Overwrites the stage and the moment the current stretch of being overdue began ({@code null} when not overdue). */
    void set(UUID vendorId, Stage stage, Instant overdueSince);

    Standing of(UUID vendorId);

    /** The stage of each shop that exists. */
    Map<UUID, Stage> stages(Collection<UUID> vendorIds);
}
