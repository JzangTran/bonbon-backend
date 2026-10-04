package com.bonbon.backend.merchant.entity;

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

/** A named set of choices owned by a shop and reusable across its dishes ("Size", "Topping"). */
@Entity
@Table(name = "option_groups")
public class OptionGroup {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "min_select", nullable = false)
    private int minSelect;

    @Column(name = "max_select", nullable = false)
    private int maxSelect;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "updated_by_type", columnDefinition = "text")
    private ActorType updatedByType;

    @Column(name = "updated_by_id")
    private UUID updatedById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OptionGroup() {
    }

    public OptionGroup(UUID vendorId, String name, int minSelect, int maxSelect) {
        this.vendorId = vendorId;
        this.name = name;
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
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

    public void rules(String name, int minSelect, int maxSelect) {
        this.name = name;
        this.minSelect = minSelect;
        this.maxSelect = maxSelect;
    }

    public void archive() {
        this.status = Status.ARCHIVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getName() {
        return name;
    }

    public int getMinSelect() {
        return minSelect;
    }

    public int getMaxSelect() {
        return maxSelect;
    }

    public Status getStatus() {
        return status;
    }
}
