package com.bonbon.backend.marketplace.category.controller;

import java.util.List;

import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.marketplace.category.dto.DisplayCategoryViews;
import com.bonbon.backend.marketplace.category.service.DisplayCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: the category strip of the home page, read by guests too. */
@Tag(name = ApiTags.CUSTOMER_BROWSE)
@RestController
@RequestMapping("/api/display-categories")
class DisplayCategoryController {

    private final DisplayCategoryService displays;

    DisplayCategoryController(DisplayCategoryService displays) {
        this.displays = displays;
    }

    @Operation(operationId = "listDisplayCategories", summary = "Danh mục ở trang chủ", description = "Dải ảnh danh mục (tên và ảnh) theo thứ tự quản trị viên đặt; chọn một danh mục để lọc quán bằng `displayCategoryId`. Không cần đăng nhập.")
    @GetMapping
    List<DisplayCategoryViews.Item> list() {
        return displays.visible();
    }
}
