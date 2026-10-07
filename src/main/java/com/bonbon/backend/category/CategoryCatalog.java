package com.bonbon.backend.category;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/** What other modules may ask of the category tree. */
public interface CategoryCatalog {

    /** True for an active level-3 category, the only place a new dish may be put. */
    boolean isAssignableLeaf(UUID categoryId);

    /**
     * The category's own rate, else the nearest ancestor's; empty when no node on the path has one (the
     * caller then applies the global default).
     */
    Optional<BigDecimal> effectiveCommissionRate(UUID categoryId);

    /** The category and every node below it (empty when it does not exist) - what a category filter matches. */
    Set<UUID> subtreeIds(UUID categoryId);

    /** Every node with the rate it carries itself, parents before children: what the commission screens are built from. */
    List<RateNode> rateNodes();

    /**
     * Sets a category's own rate, or lets it inherit again when {@code rate} is null. Not found is
     * {@code CATEGORY_NOT_FOUND}. The change is published as {@link CategoryCommissionChanged}.
     */
    void changeCommissionRate(UUID categoryId, BigDecimal rate, ActorType by, UUID actorId);

    /** {@code ownRate} is null when the node inherits. */
    record RateNode(UUID id, UUID parentId, int level, String name, boolean active, BigDecimal ownRate) {
    }
}
