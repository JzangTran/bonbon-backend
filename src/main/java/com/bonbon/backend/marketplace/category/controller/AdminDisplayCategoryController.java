package com.bonbon.backend.marketplace.category.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.marketplace.category.dto.DisplayCategoryViews;
import com.bonbon.backend.marketplace.category.service.DisplayCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The category strip of the home page, set by administrators who manage the category tree. */
@Tag(name = ApiTags.ADMIN_CATEGORIES)
@RestController
@RequestMapping("/api/admin/display-categories")
@PreAuthorize("hasAuthority('category:write')")
class AdminDisplayCategoryController {

    private final DisplayCategoryService displays;

    AdminDisplayCategoryController(DisplayCategoryService displays) {
        this.displays = displays;
    }

    @Operation(operationId = "listDisplayCategoriesForAdmin", summary = "Danh mục hiển thị (gồm danh mục ẩn)", description = "Dải ảnh danh mục ở trang chủ ứng dụng khách, theo thứ tự hiển thị, kèm các ngành cấp 3 mỗi danh mục gom lại.")
    @GetMapping
    List<DisplayCategoryViews.Admin> list() {
        return displays.all();
    }

    @Operation(operationId = "createDisplayCategory", summary = "Thêm danh mục hiển thị", description = "Tên và các ngành cấp 3 được gom vào; thêm vào cuối dải. Ảnh đặt riêng sau khi tạo.")
    @ApiError(status = 400, code = "DISPLAY_CATEGORY_LEAF_INVALID", when = "Có ngành không tồn tại hoặc không phải cấp 3.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DisplayCategoryViews.Admin create(@Valid @RequestBody DisplayCategoryViews.Create request) {
        return displays.create(request);
    }

    @Operation(operationId = "updateDisplayCategory", summary = "Sửa danh mục hiển thị", description = "Chỉ trường gửi lên mới đổi: tên, các ngành gom vào, thứ tự hoặc ẩn/hiện.")
    @ApiError(status = 404, code = "DISPLAY_CATEGORY_NOT_FOUND", when = "Danh mục không tồn tại.")
    @ApiError(status = 400, code = "DISPLAY_CATEGORY_LEAF_INVALID", when = "Có ngành không tồn tại hoặc không phải cấp 3.")
    @PatchMapping("/{id}")
    DisplayCategoryViews.Admin update(@PathVariable UUID id, @Valid @RequestBody DisplayCategoryViews.Update request) {
        return displays.update(id, request);
    }

    @Operation(operationId = "deleteDisplayCategory", summary = "Xoá danh mục hiển thị", description = "Gỡ khỏi dải ảnh; không ảnh hưởng ngành hàng hay món.")
    @ApiError(status = 404, code = "DISPLAY_CATEGORY_NOT_FOUND", when = "Danh mục không tồn tại.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        displays.delete(id);
    }

    @Operation(operationId = "setDisplayCategoryImage", summary = "Đặt ảnh danh mục hiển thị", description = "Ảnh vuông (JPG, PNG, WebP, tối đa 2 MB); ảnh cũ được xoá sau khi lưu.")
    @ApiError(status = 404, code = "DISPLAY_CATEGORY_NOT_FOUND", when = "Danh mục không tồn tại.")
    @ApiError(status = 400, code = "FILE_REQUIRED", when = "Không có tệp trong trường `file`.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Tệp vượt 2 MB.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận.")
    @PutMapping(path = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    DisplayCategoryViews.Admin setImage(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return displays.setImage(id, file);
    }

    @Operation(operationId = "removeDisplayCategoryImage", summary = "Gỡ ảnh danh mục hiển thị", description = "Danh mục trở lại ô ảnh trống.")
    @ApiError(status = 404, code = "DISPLAY_CATEGORY_NOT_FOUND", when = "Danh mục không tồn tại.")
    @DeleteMapping("/{id}/image")
    DisplayCategoryViews.Admin removeImage(@PathVariable UUID id) {
        return displays.removeImage(id);
    }
}
