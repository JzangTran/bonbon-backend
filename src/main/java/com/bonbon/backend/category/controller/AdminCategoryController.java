package com.bonbon.backend.category.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.category.dto.CategoryNode;
import com.bonbon.backend.category.dto.CategoryRequests;
import com.bonbon.backend.category.service.CategoryService;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Category management for administrators; the admin view includes hidden nodes. */
@Tag(name = ApiTags.ADMIN_CATEGORIES, description = "Cây ngành hàng 3 cấp của nền tảng; cấp 1 cố định. Tỷ lệ hoa hồng đặt theo ngành và được kế thừa xuống.")
@RestController
@RequestMapping("/api/admin/categories")
@PreAuthorize("hasAuthority('category:write')")
class AdminCategoryController {

    private final CategoryService categories;

    AdminCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @Operation(operationId = "listCategoriesForAdmin", summary = "Cây ngành hàng (gồm ngành ẩn)", description = "Toàn bộ cây, có cả ngành đã ẩn và tỷ lệ hoa hồng hiệu lực của từng ngành.")
    @GetMapping
    List<CategoryNode> tree() {
        return categories.tree(true);
    }

    @Operation(operationId = "createCategory", summary = "Thêm ngành", description = "Thêm ngành con dưới một ngành cấp 1 hoặc 2.")
    @ApiError(status = 404, code = "CATEGORY_NOT_FOUND", when = "Ngành cha không tồn tại.")
    @ApiError(status = 400, code = "CATEGORY_DEPTH_EXCEEDED", when = "Cây chỉ có 3 cấp.")
    @ApiError(status = 409, code = "CATEGORY_NAME_TAKEN", when = "Đã có ngành cùng tên trong cùng ngành cha.")
    @ApiError(status = 409, code = "CATEGORY_HAS_DISHES", when = "Ngành cha đang có món nên không thêm ngành con được.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryNode create(CurrentPrincipal principal, @Valid @RequestBody CategoryRequests.Create request) {
        return categories.create(principal, request);
    }

    @Operation(operationId = "updateCategory", summary = "Sửa ngành", description = "Đổi tên, tỷ lệ hoa hồng (`clearCommissionRate` để kế thừa), ẩn/hiện, thứ tự hoặc chuyển sang ngành cha khác cùng cấp.")
    @ApiError(status = 404, code = "CATEGORY_NOT_FOUND", when = "Ngành không tồn tại.")
    @ApiError(status = 409, code = "CATEGORY_ROOT_FIXED", when = "Ngành gốc chỉ đổi được tỷ lệ hoa hồng.")
    @ApiError(status = 409, code = "CATEGORY_NAME_TAKEN", when = "Trùng tên trong cùng ngành cha.")
    @ApiError(status = 400, code = "CATEGORY_MOVE_INVALID", when = "Ngành cha mới không hợp lệ (sai cấp hoặc tạo vòng).")
    @PatchMapping("/{id}")
    CategoryNode update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CategoryRequests.Update request) {
        return categories.update(principal, id, request);
    }

    @Operation(operationId = "deleteCategory", summary = "Xoá ngành", description = "Chỉ xoá được ngành lá chưa có món; ngành đang dùng thì ẩn đi.")
    @ApiError(status = 404, code = "CATEGORY_NOT_FOUND", when = "Ngành không tồn tại.")
    @ApiError(status = 409, code = "CATEGORY_ROOT_FIXED", when = "Không xoá được ngành gốc.")
    @ApiError(status = 409, code = "CATEGORY_HAS_CHILDREN", when = "Còn ngành con.")
    @ApiError(status = 409, code = "CATEGORY_IN_USE", when = "Ngành đang có món; hãy ẩn thay vì xoá.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        categories.delete(id);
    }
}
