package com.bonbon.backend.order.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A customer's review of the shop for one delivered order (review-order.md). */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false)
    private int rating;

    @Column(columnDefinition = "text")
    private String comment;

    /** Shortened when the review is written ("An T."): the customer's full name is never shown to others. */
    @Column(name = "reviewer_name", nullable = false, updatable = false, columnDefinition = "text")
    private String reviewerName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "hidden_at")
    private Instant hiddenAt;

    @Column(name = "hidden_reason", columnDefinition = "text")
    private String hiddenReason;

    @Column(name = "hidden_by")
    private UUID hiddenBy;

    protected Review() {
    }

    public Review(UUID orderId, UUID vendorId, UUID customerId, int rating, String comment, String reviewerName, Instant now) {
        this.orderId = orderId;
        this.vendorId = vendorId;
        this.customerId = customerId;
        this.rating = rating;
        this.comment = comment;
        this.reviewerName = reviewerName;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void edit(int rating, String comment, Instant now) {
        this.rating = rating;
        this.comment = comment;
        this.updatedAt = now;
    }

    public void hide(String reason, UUID adminId, Instant now) {
        this.hiddenAt = now;
        this.hiddenReason = reason;
        this.hiddenBy = adminId;
    }

    public void unhide() {
        this.hiddenAt = null;
        this.hiddenReason = null;
        this.hiddenBy = null;
    }

    public boolean isHidden() {
        return hiddenAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public int getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getHiddenAt() {
        return hiddenAt;
    }

    public String getHiddenReason() {
        return hiddenReason;
    }
}
