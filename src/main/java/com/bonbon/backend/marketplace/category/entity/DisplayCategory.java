package com.bonbon.backend.marketplace.category.entity;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A picture in the category strip of the customer home page. Not a node of the 3-level tree: it gathers leaf categories
 * (Trà sữa = trà sữa + trà trái cây), and picking it lists the shops with dishes in any of them.
 */
@Entity
@Table(schema = "marketplace", name = "display_categories")
public class DisplayCategory {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "image_key", columnDefinition = "text")
    private String imageKey;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "marketplace", name = "display_category_leaves", joinColumns = @JoinColumn(name = "display_category_id"))
    @Column(name = "category_id")
    private Set<UUID> leafIds = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DisplayCategory() {
    }

    public DisplayCategory(String name, int sortOrder) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImageKey() {
        return imageKey;
    }

    public void setImageKey(String imageKey) {
        this.imageKey = imageKey;
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

    public Set<UUID> getLeafIds() {
        return leafIds;
    }

    public void replaceLeafIds(Set<UUID> ids) {
        leafIds.clear();
        leafIds.addAll(ids);
    }
}
