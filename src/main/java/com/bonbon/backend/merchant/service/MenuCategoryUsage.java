package com.bonbon.backend.merchant.service;

import java.util.UUID;

import com.bonbon.backend.category.CategoryUsage;
import com.bonbon.backend.merchant.repository.MenuItemRepository;
import org.springframework.stereotype.Component;

/** Tells the category tree how many dishes sit on a node, so a used category is hidden rather than deleted. */
@Component
class MenuCategoryUsage implements CategoryUsage {

    private final MenuItemRepository items;

    MenuCategoryUsage(MenuItemRepository items) {
        this.items = items;
    }

    @Override
    public long dishCount(UUID categoryId) {
        return items.countByCategoryId(categoryId);
    }
}
