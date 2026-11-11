/**
 * The platform's 3-level dish category tree and the commission rate set on its nodes. Other modules read it
 * through {@link com.bonbon.backend.marketplace.category.CategoryCatalog}; whether a category is used by dishes is
 * reported back through {@link com.bonbon.backend.marketplace.category.CategoryUsage}, so this module depends on none.
 */
@NamedInterface("category")
package com.bonbon.backend.marketplace.category;

import org.springframework.modulith.NamedInterface;
