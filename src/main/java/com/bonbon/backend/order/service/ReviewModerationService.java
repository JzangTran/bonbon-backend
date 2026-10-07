package com.bonbon.backend.order.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.entity.Review;
import com.bonbon.backend.order.entity.ReviewResponse;
import com.bonbon.backend.order.repository.ReviewRepository;
import com.bonbon.backend.order.repository.ReviewResponseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator hiding or restoring a review or a shop's reply (moderate-review.md). Nothing is deleted: the row
 * keeps its state and every action is also appended to {@code review_moderation_log} with who and why.
 */
@Service
public class ReviewModerationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviews;
    private final ReviewResponseRepository responses;
    private final ReviewService reviewService;
    private final JdbcClient jdbc;

    ReviewModerationService(ReviewRepository reviews, ReviewResponseRepository responses, ReviewService reviewService, JdbcClient jdbc) {
        this.reviews = reviews;
        this.responses = responses;
        this.reviewService = reviewService;
        this.jdbc = jdbc;
    }

    /** Newest first; {@code hidden} and {@code maxRating} (for finding bad reviews) and {@code vendorId} are all optional. */
    @Transactional(readOnly = true)
    public ReviewViews.Page list(UUID vendorId, Boolean hidden, Integer maxRating, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        StringBuilder where = new StringBuilder(" where true");
        Map<String, Object> params = new HashMap<>();
        if (vendorId != null) {
            where.append(" and vendor_id = :vendorId");
            params.put("vendorId", vendorId);
        }
        if (hidden != null) {
            where.append(hidden ? " and hidden_at is not null" : " and hidden_at is null");
        }
        if (maxRating != null) {
            where.append(" and rating <= :maxRating");
            params.put("maxRating", maxRating);
        }
        long total = jdbc.sql("select count(*) from reviews" + where).params(params).query(Long.class).single();
        List<UUID> ids = jdbc.sql("select id from reviews" + where + " order by created_at desc, id desc limit :limit offset :offset")
                .params(params).param("limit", size).param("offset", (long) page * size).query(UUID.class).list();
        Map<UUID, Review> byId = new HashMap<>();
        reviews.findAllById(ids).forEach(r -> byId.put(r.getId(), r));
        List<Review> ordered = new ArrayList<>();
        ids.forEach(id -> ordered.add(byId.get(id)));
        Map<UUID, ReviewResponse> replies = reviewService.repliesOf(ordered);
        Map<UUID, Long> numbers = reviewService.numbers(ordered.stream().map(Review::getOrderId).toList());
        List<ReviewViews.Review> items = ordered.stream()
                .map(r -> reviewService.adminView(r, numbers.get(r.getOrderId()), replies.get(r.getId()))).toList();
        return new ReviewViews.Page(items, page, size, total, null, null);
    }

    @Transactional
    public ReviewViews.Review hideReview(UUID adminId, UUID reviewId, String reason) {
        Review review = review(reviewId);
        if (review.isHidden()) {
            throw BusinessException.conflict("ALREADY_HIDDEN", "Đánh giá này đã bị ẩn.");
        }
        review.hide(reason.strip(), adminId, Instant.now());
        reviews.saveAndFlush(review);
        log("REVIEW", reviewId, "HIDE", reason.strip(), adminId);
        reviewService.refreshRating(review.getVendorId());
        return view(review);
    }

    @Transactional
    public ReviewViews.Review unhideReview(UUID adminId, UUID reviewId) {
        Review review = review(reviewId);
        if (!review.isHidden()) {
            throw BusinessException.conflict("NOT_HIDDEN", "Đánh giá này không bị ẩn.");
        }
        review.unhide();
        reviews.saveAndFlush(review);
        log("REVIEW", reviewId, "UNHIDE", null, adminId);
        reviewService.refreshRating(review.getVendorId());
        return view(review);
    }

    @Transactional
    public ReviewViews.Review hideResponse(UUID adminId, UUID responseId, String reason) {
        ReviewResponse reply = response(responseId);
        if (reply.isHidden()) {
            throw BusinessException.conflict("ALREADY_HIDDEN", "Phản hồi này đã bị ẩn.");
        }
        reply.hide(reason.strip(), adminId, Instant.now());
        responses.saveAndFlush(reply);
        log("RESPONSE", responseId, "HIDE", reason.strip(), adminId);
        return view(review(reply.getReviewId()));
    }

    @Transactional
    public ReviewViews.Review unhideResponse(UUID adminId, UUID responseId) {
        ReviewResponse reply = response(responseId);
        if (!reply.isHidden()) {
            throw BusinessException.conflict("NOT_HIDDEN", "Phản hồi này không bị ẩn.");
        }
        reply.unhide();
        responses.saveAndFlush(reply);
        log("RESPONSE", responseId, "UNHIDE", null, adminId);
        return view(review(reply.getReviewId()));
    }

    private Review review(UUID id) {
        return reviews.findById(id).orElseThrow(() -> BusinessException.notFound("REVIEW_NOT_FOUND", "Không tìm thấy đánh giá."));
    }

    private ReviewResponse response(UUID id) {
        return responses.findById(id).orElseThrow(() -> BusinessException.notFound("RESPONSE_NOT_FOUND", "Không tìm thấy phản hồi."));
    }

    private ReviewViews.Review view(Review review) {
        return reviewService.adminView(review, reviewService.numbers(List.of(review.getOrderId())).get(review.getOrderId()),
                responses.findByReviewId(review.getId()).orElse(null));
    }

    private void log(String targetType, UUID targetId, String action, String reason, UUID adminId) {
        jdbc.sql("insert into review_moderation_log (target_type, target_id, action, reason, admin_id) values (:t, :id, :a, :r, :admin)")
                .param("t", targetType).param("id", targetId).param("a", action).param("r", reason).param("admin", adminId).update();
    }
}
