package com.bonbon.backend.order.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The shop's one reply to a review (respond-to-review.md). */
@Entity
@Table(name = "review_responses")
public class ReviewResponse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(nullable = false, columnDefinition = "text")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "acted_by_type", nullable = false, columnDefinition = "text")
    private ActorType actedByType;

    @Column(name = "acted_by_id", nullable = false)
    private UUID actedById;

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

    protected ReviewResponse() {
    }

    public ReviewResponse(UUID reviewId, String text, ActorType actedByType, UUID actedById, Instant now) {
        this.reviewId = reviewId;
        this.text = text;
        this.actedByType = actedByType;
        this.actedById = actedById;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void edit(String text, ActorType by, UUID actorId, Instant now) {
        this.text = text;
        this.actedByType = by;
        this.actedById = actorId;
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

    public UUID getReviewId() {
        return reviewId;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getHiddenReason() {
        return hiddenReason;
    }
}
