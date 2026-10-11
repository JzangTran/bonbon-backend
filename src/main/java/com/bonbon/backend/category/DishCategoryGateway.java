package com.bonbon.backend.category;

import java.util.Optional;
import java.util.UUID;

/**
 * What category moderation needs from the menu, reported back by the module that owns dishes (the same way {@link CategoryUsage}
 * works), so the category module depends on none.
 */
public interface DishCategoryGateway {

    record Dish(UUID id, UUID vendorId, String shopName, String name, UUID categoryId, boolean archived) {
    }

    Optional<Dish> dish(UUID dishId);

    /** Puts the dish in this category (already checked to be an assignable leaf). The rate of orders placed afterwards follows it. */
    void setCategory(UUID dishId, UUID categoryId);

    /** A restricted dish is dropped from search and category results; it stays on its menu and orderable. */
    void setRestricted(UUID dishId, boolean restricted);

    /** The shop this account owns while it is operating (approved, suspended or closing): the shop whose cases the person may see. */
    Optional<UUID> operatingShopOf(UUID ownerUserId);
}
