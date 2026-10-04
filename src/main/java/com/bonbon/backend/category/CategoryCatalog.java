package com.bonbon.backend.category;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
}
