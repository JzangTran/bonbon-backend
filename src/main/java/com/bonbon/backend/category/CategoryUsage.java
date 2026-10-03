package com.bonbon.backend.category;

import java.util.UUID;

/**
 * Implemented by the module that owns dishes (merchant): how many dishes sit on a category. A used category
 * cannot be deleted (only hidden) and cannot get children. No implementation means nothing uses categories.
 */
public interface CategoryUsage {

    long dishCount(UUID categoryId);
}
