package com.bonbon.backend.category.entity;

import java.math.BigDecimal;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "categories")
public class Category {

    public static final int MAX_LEVEL = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false)
    private short level;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "acted_by_type", columnDefinition = "text")
    private ActorType actedByType;

    @Column(name = "acted_by_id")
    private UUID actedById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Category() {
    }

    public Category(Category parent, String name, int sortOrder) {
        this.parentId = parent.id;
        this.level = (short) (parent.level + 1);
        this.name = name;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void actedBy(ActorType type, UUID id) {
        this.actedByType = type;
        this.actedById = id;
    }

    public void moveUnder(Category newParent) {
        this.parentId = newParent.id;
    }

    public boolean isRoot() {
        return parentId == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getParentId() {
        return parentId;
    }

    public int getLevel() {
        return level;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }
}
