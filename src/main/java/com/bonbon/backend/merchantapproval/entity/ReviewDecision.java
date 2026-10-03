package com.bonbon.backend.merchantapproval.entity;

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

@Entity
@Table(name = "vendor_review_decisions")
public class ReviewDecision {

    public enum Decision { APPROVED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Decision decision;

    @Column(columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by_type", nullable = false, columnDefinition = "text")
    private ActorType decidedByType;

    @Column(name = "decided_by_id", nullable = false)
    private UUID decidedById;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    protected ReviewDecision() {
    }

    public ReviewDecision(UUID vendorId, Decision decision, String reason, ActorType decidedByType, UUID decidedById) {
        this.vendorId = vendorId;
        this.decision = decision;
        this.reason = reason;
        this.decidedByType = decidedByType;
        this.decidedById = decidedById;
        this.decidedAt = Instant.now();
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public UUID getDecidedById() {
        return decidedById;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
