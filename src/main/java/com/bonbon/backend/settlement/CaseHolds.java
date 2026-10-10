package com.bonbon.backend.settlement;

import java.util.UUID;

/**
 * Money set aside for an undecided order case, so the shop cannot be paid out ahead of a pending refund
 * (flows/shop-performance/review-order-cases.md). Placing and releasing are safe to repeat.
 */
public interface CaseHolds {

    /** Holds {@code amount} of the shop's balance until the case is decided. */
    void place(UUID caseId, UUID vendorId, int amount);

    /** Lets go of what the case held, whatever the decision. */
    void release(UUID caseId);

    /**
     * The shop bears an upheld case: the refund and the matching commission reversal are posted to its ledger, once per
     * case, and what the case held is let go. Cases that were dismissed only call {@link #release}.
     */
    void reverse(UUID caseId, UUID vendorId, UUID orderId, String note, com.bonbon.backend.common.persistence.ActorType by, UUID actorId);

    void bear(UUID caseId, UUID vendorId, UUID orderId, int refund, int commissionReversal, String note, com.bonbon.backend.common.persistence.ActorType by,
            UUID actorId);
}
