package com.bonbon.backend.ordering.order.service;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.ordering.order.ReviewReports;
import com.bonbon.backend.ordering.order.repository.ReviewRepository;
import com.bonbon.backend.ordering.order.repository.ReviewResponseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewReportsService implements ReviewReports {

    private final ReviewRepository reviews;
    private final ReviewResponseRepository responses;
    private final ReviewModerationService moderation;

    ReviewReportsService(ReviewRepository reviews, ReviewResponseRepository responses, ReviewModerationService moderation) {
        this.reviews = reviews;
        this.responses = responses;
        this.moderation = moderation;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewSnapshot> review(UUID reviewId) {
        return reviews.findById(reviewId).map(r -> new ReviewSnapshot(r.getId(), r.getOrderId(), r.getVendorId(), r.getCustomerId(), r.getRating(),
                r.getComment(), r.getReviewerName(), r.getCreatedAt(), r.isHidden()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResponseSnapshot> response(UUID responseId) {
        return responses.findById(responseId).flatMap(reply -> reviews.findById(reply.getReviewId()).map(review ->
                new ResponseSnapshot(reply.getId(), reply.getReviewId(), review.getVendorId(), reply.getText(), reply.getCreatedAt(), reply.isHidden())));
    }

    @Override
    @Transactional
    public void hideReview(UUID adminId, UUID reviewId, String reason) {
        if (review(reviewId).filter(r -> !r.hidden()).isPresent()) {
            moderation.hideReview(adminId, reviewId, reason);
        }
    }

    @Override
    @Transactional
    public void unhideReview(UUID adminId, UUID reviewId) {
        if (review(reviewId).filter(ReviewSnapshot::hidden).isPresent()) {
            moderation.unhideReview(adminId, reviewId);
        }
    }

    @Override
    @Transactional
    public void unhideResponse(UUID adminId, UUID responseId) {
        if (response(responseId).filter(ResponseSnapshot::hidden).isPresent()) {
            moderation.unhideResponse(adminId, responseId);
        }
    }

    @Override
    @Transactional
    public void hideResponse(UUID adminId, UUID responseId, String reason) {
        if (response(responseId).filter(r -> !r.hidden()).isPresent()) {
            moderation.hideResponse(adminId, responseId, reason);
        }
    }
}
