package com.bonbon.backend.ordering.order;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What reporting (support) needs from reviews: a snapshot of the reported review or reply as evidence, and hiding it when an
 * administrator upholds the report, through the same moderation path as hiding it by hand (moderate-review.md).
 */
public interface ReviewReports {

    record ReviewSnapshot(UUID id, UUID orderId, UUID vendorId, UUID customerId, int rating, String comment, String reviewerName,
            Instant createdAt, boolean hidden) {
    }

    record ResponseSnapshot(UUID id, UUID reviewId, UUID vendorId, String text, Instant createdAt, boolean hidden) {
    }

    Optional<ReviewSnapshot> review(UUID reviewId);

    Optional<ResponseSnapshot> response(UUID responseId);

    /** Hides the review (the shop's rating is recomputed); doing nothing if it is hidden already. */
    void hideReview(UUID adminId, UUID reviewId, String reason);

    /** Hides the shop's reply; doing nothing if it is hidden already. */
    void hideResponse(UUID adminId, UUID responseId, String reason);

    /** Shows the review again after a contested decision is overturned; nothing if it is shown already. */
    void unhideReview(UUID adminId, UUID reviewId);

    void unhideResponse(UUID adminId, UUID responseId);
}
