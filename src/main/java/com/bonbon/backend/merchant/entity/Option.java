package com.bonbon.backend.merchant.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.merchant.OptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One choice inside an {@link OptionGroup}, with the amount it adds to the dish price. */
@Entity
@Table(name = "options")
public class Option {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "price_delta", nullable = false)
    private int priceDelta;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private OptionStatus status = OptionStatus.AVAILABLE;

    @Column(name = "is_default", nullable = false)
    private boolean defaultChoice;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "updated_by_type", columnDefinition = "text")
    private ActorType updatedByType;

    @Column(name = "updated_by_id")
    private UUID updatedById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Option() {
    }

    public Option(UUID groupId) {
        this.groupId = groupId;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touchedBy(ActorType type, UUID id) {
        this.updatedByType = type;
        this.updatedById = id;
    }

    public void set(String name, int priceDelta, OptionStatus status, boolean defaultChoice, int displayOrder) {
        this.name = name;
        this.priceDelta = priceDelta;
        this.status = status;
        this.defaultChoice = defaultChoice;
        this.displayOrder = displayOrder;
    }

    public void archive() {
        this.status = OptionStatus.ARCHIVED;
        this.defaultChoice = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getName() {
        return name;
    }

    public int getPriceDelta() {
        return priceDelta;
    }

    public OptionStatus getStatus() {
        return status;
    }

    public void setStatus(OptionStatus status) {
        this.status = status;
    }

    public boolean isDefaultChoice() {
        return defaultChoice;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }
}
