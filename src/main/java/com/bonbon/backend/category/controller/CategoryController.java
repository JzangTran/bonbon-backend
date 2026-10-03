package com.bonbon.backend.category.controller;

import java.time.Duration;
import java.util.List;

import com.bonbon.backend.category.dto.CategoryNode;
import com.bonbon.backend.category.service.CategoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The visible tree, for browsing filters and the dish form. Public and briefly cacheable. */
@RestController
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping("/api/categories")
    ResponseEntity<List<CategoryNode>> tree() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(categories.tree(false));
    }
}
