package com.bonbon.backend.order.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.merchant.ShopRatings;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.ShopReviews;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.entity.Review;
import com.bonbon.backend.order.entity.ReviewResponse;
import com.bonbon.backend.order.repository.OrderRepository;
import com.bonbon.backend.order.repository.ReviewRepository;
import com.bonbon.backend.order.repository.ReviewResponseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviews of delivered orders (review-order.md), the shop's reply (respond-to-review.md) and what the public sees.
 * A customer's review and a shop's reply share one edit window, so neither side needs a separate justification.
 */
@Service
public class ReviewService implements ShopReviews {

    static final String EDIT_WINDOW_KEY = "review.edit_window_hours";
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviews;
    private final ReviewResponseRepository responses;
    private final OrderRepository orders;
    private final ShopOrdering shops;
    private final ShopRatings ratings;
    private final SystemSettingsService settings;
    private final JdbcClient jdbc;

    ReviewService(ReviewRepository reviews, ReviewResponseRepository responses, OrderRepository orders, ShopOrdering shops,
            ShopRatings ratings, SystemSettingsService settings, JdbcClient jdbc) {
        this.reviews = reviews;
        this.responses = responses;
        this.orders = orders;
        this.shops = shops;
        this.ratings = ratings;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    // --- the customer

    @Transactional
    public ReviewViews.Review post(UUID customerId, UUID orderId, int rating, String comment) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw BusinessException.conflict("REVIEW_NOT_ALLOWED", "Chỉ đánh giá được đơn đã giao.");
        }
        if (reviews.existsByOrderId(orderId)) {
            throw BusinessException.conflict("ALREADY_REVIEWED", "Bạn đã đánh giá đơn này rồi.");
        }
        Review review = new Review(orderId, order.getVendorId(), customerId, rating, clean(comment),
                ReviewerNames.shorten(order.getDeliveryName()), Instant.now());
        try {
            reviews.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("ALREADY_REVIEWED", "Bạn đã đánh giá đơn này rồi.");
        }
        refreshRating(order.getVendorId());
        return customerView(review, order.getNumber(), null);
    }

    @Transactional(readOnly = true)
    public ReviewViews.Review mine(UUID customerId, UUID orderId) {
        Order order = ownOrder(customerId, orderId);
        Review review = reviews.findByOrderId(orderId)
                .orElseThrow(() -> BusinessException.notFound("REVIEW_NOT_FOUND", "Đơn này chưa có đánh giá."));
        return customerView(review, order.getNumber(), responses.findByReviewId(review.getId()).orElse(null));
    }

    @Transactional
    public ReviewViews.Review edit(UUID customerId, UUID orderId, int rating, String comment) {
        Order order = ownOrder(customerId, orderId);
        Review review = editable(orderId);
        review.edit(rating, clean(comment), Instant.now());
        reviews.saveAndFlush(review);
        refreshRating(review.getVendorId());
        return customerView(review, order.getNumber(), responses.findByReviewId(review.getId()).orElse(null));
    }

    @Transactional
    public void delete(UUID customerId, UUID orderId) {
        ownOrder(customerId, orderId);
        Review review = editable(orderId);
        reviews.delete(review);
        reviews.flush();
        refreshRating(review.getVendorId());
    }

    // --- the public

    @Transactional(readOnly = true)
    public ReviewViews.Page forShop(UUID vendorId, int page, int size) {
        checkPage(page, size);
        shops.shop(vendorId).orElseThrow(() -> BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán."));
        Page<Review> found = reviews.findVisibleByVendor(vendorId, PageRequest.of(page, size, newestFirst()));
        Map<UUID, ReviewResponse> replies = repliesOf(found.getContent());
        List<ReviewViews.Review> items = found.getContent().stream().map(r -> publicView(r, replies.get(r.getId()))).toList();
        ShopRatings.RatingTotals totals = totals(vendorId);
        Double average = totals.count() == 0 ? null : Math.round(totals.sum() * 10.0 / totals.count()) / 10.0;
        return new ReviewViews.Page(items, page, size, found.getTotalElements(), average, totals.count());
    }

    // --- the shop

    @Override
    @Transactional(readOnly = true)
    public ReviewViews.Page list(UUID vendorId, boolean unrepliedOnly, int page, int size) {
        checkPage(page, size);
        Page<Review> found = reviews.findForShop(vendorId, !unrepliedOnly, PageRequest.of(page, size, newestFirst()));
        Map<UUID, ReviewResponse> replies = repliesOf(found.getContent());
        Map<UUID, Long> numbers = numbers(found.getContent().stream().map(Review::getOrderId).toList());
        List<ReviewViews.Review> items = found.getContent().stream()
                .map(r -> shopView(r, numbers.get(r.getOrderId()), replies.get(r.getId()))).toList();
        return new ReviewViews.Page(items, page, size, found.getTotalElements(), null, null);
    }

    @Override
    @Transactional
    public ReviewViews.Review respond(UUID vendorId, UUID reviewId, String text, ActorType by, UUID actorId) {
        Review review = ownReview(vendorId, reviewId);
        if (responses.findByReviewId(reviewId).isPresent()) {
            throw BusinessException.conflict("ALREADY_RESPONDED", "Đánh giá này đã có phản hồi.");
        }
        try {
            responses.saveAndFlush(new ReviewResponse(reviewId, text.strip(), by, actorId, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("ALREADY_RESPONDED", "Đánh giá này đã có phản hồi.");
        }
        return shopView(review, number(review), responses.findByReviewId(reviewId).orElseThrow());
    }

    @Override
    @Transactional
    public ReviewViews.Review editResponse(UUID vendorId, UUID reviewId, String text, ActorType by, UUID actorId) {
        Review review = ownReview(vendorId, reviewId);
        ReviewResponse reply = editableResponse(reviewId);
        reply.edit(text.strip(), by, actorId, Instant.now());
        responses.saveAndFlush(reply);
        return shopView(review, number(review), reply);
    }

    @Override
    @Transactional
    public void deleteResponse(UUID vendorId, UUID reviewId) {
        ownReview(vendorId, reviewId);
        responses.delete(editableResponse(reviewId));
    }

    // --- shared with moderation

    /** Brings the shop's stored average in step with the visible reviews; call it in the transaction that changed one. */
    void refreshRating(UUID vendorId) {
        reviews.flush();
        ratings.refresh(vendorId, () -> totals(vendorId));
    }

    Duration editWindow() {
        return Duration.ofHours(settings.getLong(EDIT_WINDOW_KEY, 24));
    }

    Map<UUID, Long> numbers(Collection<UUID> orderIds) {
        Map<UUID, Long> result = new HashMap<>();
        orders.findAllById(orderIds).forEach(o -> result.put(o.getId(), o.getNumber()));
        return result;
    }

    Map<UUID, ReviewResponse> repliesOf(Collection<Review> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return responses.findByReviewIdIn(list.stream().map(Review::getId).toList()).stream()
                .collect(Collectors.toMap(ReviewResponse::getReviewId, r -> r));
    }

    ReviewViews.Review adminView(Review r, Long orderNumber, ReviewResponse reply) {
        return new ReviewViews.Review(r.getId(), r.getOrderId(), orderNumber, r.getVendorId(), r.getRating(), r.getComment(),
                r.getReviewerName(), r.getCreatedAt(), r.getUpdatedAt(), null, r.isHidden(), r.getHiddenReason(),
                reply == null ? null : new ReviewViews.Reply(reply.getId(), reply.getText(), reply.getCreatedAt(), reply.getUpdatedAt(),
                        null, reply.isHidden(), reply.getHiddenReason()));
    }

    // --- internals

    private ShopRatings.RatingTotals totals(UUID vendorId) {
        return jdbc.sql("select coalesce(sum(rating), 0)::int, count(*)::int from reviews where vendor_id = :v and hidden_at is null")
                .param("v", vendorId).query((rs, n) -> new ShopRatings.RatingTotals(rs.getInt(1), rs.getInt(2))).single();
    }

    private Order ownOrder(UUID customerId, UUID orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
    }

    /** The review of this order if the customer may still change it. */
    private Review editable(UUID orderId) {
        Review review = reviews.findByOrderId(orderId)
                .orElseThrow(() -> BusinessException.notFound("REVIEW_NOT_FOUND", "Đơn này chưa có đánh giá."));
        if (review.isHidden()) {
            // Otherwise a hidden review could be deleted and rewritten to dodge the moderator's decision.
            throw BusinessException.conflict("REVIEW_HIDDEN", "Đánh giá này đã bị ẩn nên không sửa hoặc xoá được.");
        }
        if (Instant.now().isAfter(review.getCreatedAt().plus(editWindow()))) {
            throw BusinessException.conflict("REVIEW_LOCKED", "Đã quá thời hạn sửa hoặc xoá đánh giá.");
        }
        return review;
    }

    private Review ownReview(UUID vendorId, UUID reviewId) {
        return reviews.findById(reviewId).filter(r -> r.getVendorId().equals(vendorId) && !r.isHidden())
                .orElseThrow(() -> BusinessException.notFound("REVIEW_NOT_FOUND", "Không tìm thấy đánh giá."));
    }

    private ReviewResponse editableResponse(UUID reviewId) {
        ReviewResponse reply = responses.findByReviewId(reviewId)
                .orElseThrow(() -> BusinessException.notFound("RESPONSE_NOT_FOUND", "Đánh giá này chưa có phản hồi."));
        if (reply.isHidden()) {
            throw BusinessException.conflict("RESPONSE_HIDDEN", "Phản hồi này đã bị ẩn nên không sửa hoặc xoá được.");
        }
        if (Instant.now().isAfter(reply.getCreatedAt().plus(editWindow()))) {
            throw BusinessException.conflict("RESPONSE_LOCKED", "Đã quá thời hạn sửa hoặc xoá phản hồi.");
        }
        return reply;
    }

    private Long number(Review review) {
        return numbers(List.of(review.getOrderId())).get(review.getOrderId());
    }

    private ReviewViews.Review customerView(Review r, Long orderNumber, ReviewResponse reply) {
        // A hidden reply is no longer shown to the customer.
        ReviewViews.Reply shown = reply == null || reply.isHidden() ? null
                : new ReviewViews.Reply(null, reply.getText(), reply.getCreatedAt(), reply.getUpdatedAt(), null, null, null);
        Instant until = r.isHidden() ? null : r.getCreatedAt().plus(editWindow());
        return new ReviewViews.Review(r.getId(), r.getOrderId(), orderNumber, r.getVendorId(), r.getRating(), r.getComment(),
                r.getReviewerName(), r.getCreatedAt(), r.getUpdatedAt(), until, r.isHidden(), r.getHiddenReason(), shown);
    }

    private ReviewViews.Review shopView(Review r, Long orderNumber, ReviewResponse reply) {
        ReviewViews.Reply shown = reply == null ? null : new ReviewViews.Reply(reply.getId(), reply.getText(), reply.getCreatedAt(),
                reply.getUpdatedAt(), reply.isHidden() ? null : reply.getCreatedAt().plus(editWindow()), reply.isHidden(),
                reply.getHiddenReason());
        return new ReviewViews.Review(r.getId(), r.getOrderId(), orderNumber, null, r.getRating(), r.getComment(), r.getReviewerName(),
                r.getCreatedAt(), r.getUpdatedAt(), null, null, null, shown);
    }

    private ReviewViews.Review publicView(Review r, ReviewResponse reply) {
        ReviewViews.Reply shown = reply == null || reply.isHidden() ? null
                : new ReviewViews.Reply(null, reply.getText(), reply.getCreatedAt(), reply.getUpdatedAt(), null, null, null);
        return new ReviewViews.Review(r.getId(), null, null, null, r.getRating(), r.getComment(), r.getReviewerName(), r.getCreatedAt(),
                r.getUpdatedAt(), null, null, null, shown);
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
    }

    private static String clean(String comment) {
        return comment == null || comment.isBlank() ? null : comment.strip();
    }
}
