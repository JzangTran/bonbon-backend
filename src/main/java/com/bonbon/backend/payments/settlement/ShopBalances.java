package com.bonbon.backend.payments.settlement;

import java.util.UUID;

/** Where a shop stands in the ledger, for deciding whether it can be closed. */
public interface ShopBalances {

    /** Positive: the platform owes the shop. Negative: the shop owes the platform. Zero: settled. */
    long balance(UUID vendorId);

    /** Money set aside for cases that are not decided yet. */
    long heldForCases(UUID vendorId);
}
