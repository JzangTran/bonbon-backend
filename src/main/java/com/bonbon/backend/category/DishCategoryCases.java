package com.bonbon.backend.category;

import java.util.UUID;

/** Called by the menu when a seller edits a dish, so a seller who fixes the category closes the case about it. */
public interface DishCategoryCases {

    /**
     * The seller changed the dish's category in an ordinary edit: an open case that is RESTRICTED or AUTO_CORRECTED is closed as RESOLVED
     * (whatever category was chosen) and the restriction is lifted. An appeal in progress is left alone.
     */
    void sellerChangedCategory(UUID dishId, UUID newCategoryId, UUID sellerUserId);
}
