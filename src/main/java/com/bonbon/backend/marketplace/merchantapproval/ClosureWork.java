package com.bonbon.backend.marketplace.merchantapproval;

import java.util.UUID;

/**
 * Work that still ties a closing shop down (flows/merchant/close-shop.md). Each module that holds such work answers for its own
 * part (orders still going, undecided cases, money held or owed), so shop closure never reaches into those modules itself.
 */
public interface ClosureWork {

    /** Adds what this module still has open for the shop. */
    void count(UUID vendorId, ClosureTally tally);
}
