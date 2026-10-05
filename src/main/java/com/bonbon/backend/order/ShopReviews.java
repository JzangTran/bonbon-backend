package com.bonbon.backend.order;

import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/**
 * A shop reading the reviews of its own orders and replying to them (respond-to-review.md). Every call is scoped
 * to one shop: a review of another shop is simply not found. The caller has already proved it acts for
 * {@code vendorId}.
 */
public interface ShopReviews {

    /** Newest first; hidden reviews never appear. {@code unrepliedOnly} narrows it to the ones still waiting for a reply. */
    ReviewViews.Page list(UUID vendorId, boolean unrepliedOnly, int page, int size);

    /** The one reply to a review; only while there is none yet. */
    ReviewViews.Review respond(UUID vendorId, UUID reviewId, String text, ActorType by, UUID actorId);

    /** Rewrites the reply, within the same 24 hours as the customer's own edit window. */
    ReviewViews.Review editResponse(UUID vendorId, UUID reviewId, String text, ActorType by, UUID actorId);

    /** Removes the reply, within the edit window. */
    void deleteResponse(UUID vendorId, UUID reviewId);
}
