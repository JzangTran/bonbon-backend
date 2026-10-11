package com.bonbon.backend.shopperformance;

import java.util.UUID;

/** What is still undecided about a shop, for deciding whether it can be closed. */
public interface ShopCaseStatus {

    /** Cases waiting for the shop, the customer or an administrator (AWAITING_SHOP, AWAITING_CUSTOMER, OPEN). */
    long openCases(UUID vendorId);

    /** Penalty appeals waiting for an administrator. */
    long pendingAppeals(UUID vendorId);
}
