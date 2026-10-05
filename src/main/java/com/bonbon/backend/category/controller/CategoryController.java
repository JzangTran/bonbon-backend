package com.bonbon.backend.category.controller;

import java.time.Duration;
import java.util.List;

import com.bonbon.backend.category.dto.CategoryNode;
import com.bonbon.backend.category.service.CategoryService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The visible tree, for browsing filters and the dish form. Public and briefly cacheable. */
@Tag(name = ApiTags.CATEGORIES, description = "Cây ngành hàng công khai: bộ lọc khi tìm quán và lựa chọn ngành cho món.")
@RestController
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @Operation(operationId = "listCategories", summary = "Cây ngành hàng", description = "Chỉ các ngành đang hiện; ngành ẩn biến mất cùng cả nhánh con. Được phép cache ngắn.")
    @GetMapping("/api/categories")
    ResponseEntity<List<CategoryNode>> tree() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(categories.tree(false));
    }
}
