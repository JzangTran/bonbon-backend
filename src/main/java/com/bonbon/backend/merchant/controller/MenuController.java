package com.bonbon.backend.merchant.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.MenuRequests;
import com.bonbon.backend.merchant.dto.MenuView;
import com.bonbon.backend.merchant.dto.OptionRequests;
import com.bonbon.backend.merchant.service.MenuService;
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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The caller's own menu. Every write answers with the whole menu so a client can simply replace its copy. */
@Tag(name = ApiTags.SELLER_MENU, description = "Thực đơn của quán người gọi: mục và món. Mọi thao tác ghi trả về toàn bộ thực đơn để client thay bản sao của mình.")
@RestController
@RequestMapping("/api/merchant")
class MenuController {

    private final MenuService menu;

    MenuController(MenuService menu) {
        this.menu = menu;
    }

    @Operation(operationId = "getMyMenu", summary = "Xem thực đơn", description = "Các mục theo thứ tự, mỗi mục kèm món; `soldOut` là trạng thái hết thật sự (tắt bán, hết tồn kho hoặc thiếu lựa chọn bắt buộc).")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @GetMapping("/menu")
    @PreAuthorize("hasAuthority('vendor:read')")
    MenuView get(CurrentPrincipal principal) {
        return menu.menu(principal);
    }

    @Operation(operationId = "createMenuSection", summary = "Thêm mục", description = "Tên mục là duy nhất trong quán (không phân biệt hoa thường).")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 409, code = "MENU_SECTION_NAME_TAKEN", when = "Đã có mục cùng tên.")
    @PostMapping("/menu-sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView createSection(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.Section request) {
        return menu.createSection(principal, request);
    }

    /** Bulk reorder: the full list of section ids in the new order. */
    @Operation(operationId = "reorderMenuSections", summary = "Sắp xếp mục", description = "Gửi đầy đủ id các mục theo thứ tự mới.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "MENU_ORDER_MISMATCH", when = "Danh sách thiếu, thừa hoặc trùng mục.")
    @PutMapping("/menu-sections/order")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView reorderSections(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.Order request) {
        return menu.reorderSections(principal, request);
    }

    @Operation(operationId = "renameMenuSection", summary = "Đổi tên mục", description = "Tên mục là duy nhất trong quán (không phân biệt hoa thường).")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_SECTION_NOT_FOUND", when = "Mục không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 409, code = "MENU_SECTION_NAME_TAKEN", when = "Đã có mục cùng tên.")
    @PatchMapping("/menu-sections/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView renameSection(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.Section request) {
        return menu.renameSection(principal, id, request);
    }

    @Operation(operationId = "deleteMenuSection", summary = "Xoá mục", description = "Chỉ xoá được mục không còn món.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_SECTION_NOT_FOUND", when = "Mục không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 409, code = "SECTION_NOT_EMPTY", when = "Mục còn món: chuyển hoặc xoá món trước.")
    @DeleteMapping("/menu-sections/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView deleteSection(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.deleteSection(principal, id);
    }

    @Operation(operationId = "reorderMenuItems", summary = "Sắp xếp món trong mục", description = "Gửi đầy đủ id các món của mục theo thứ tự mới.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_SECTION_NOT_FOUND", when = "Mục không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "MENU_ORDER_MISMATCH", when = "Danh sách thiếu, thừa hoặc trùng món.")
    @PutMapping("/menu-sections/{id}/items/order")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView reorderItems(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.Order request) {
        return menu.reorderItems(principal, id, request);
    }

    @Operation(operationId = "createMenuItem", summary = "Thêm món", description = "Giá là số nguyên VND. Ngành hàng phải là ngành cấp 3 đang hoạt động (quyết định hoa hồng). `stockQuantity` bỏ trống là không giới hạn.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_SECTION_NOT_FOUND", when = "Mục không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành hàng không phải ngành cấp 3 đang hoạt động.")
    @PostMapping("/menu-items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView createItem(CurrentPrincipal principal, @Valid @RequestBody MenuRequests.ItemCreate request) {
        return menu.createItem(principal, request);
    }

    @Operation(operationId = "updateMenuItem", summary = "Sửa món", description = "Chỉ trường gửi lên mới đổi; `clearDescription`/`clearStock` xoá mô tả/tồn kho. Đơn đã đặt giữ giá và tên cũ.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @ApiError(status = 404, code = "MENU_SECTION_NOT_FOUND", when = "Mục không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành hàng không phải ngành cấp 3 đang hoạt động.")
    @PatchMapping("/menu-items/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView updateItem(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.ItemUpdate request) {
        return menu.updateItem(principal, id, request);
    }

    /** Archives the dish; orders that reference it keep reading it. */
    @Operation(operationId = "deleteMenuItem", summary = "Xoá món", description = "Món được lưu trữ chứ không xoá hẳn: biến mất khỏi thực đơn nhưng đơn cũ vẫn đọc được.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @DeleteMapping("/menu-items/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView deleteItem(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.deleteItem(principal, id);
    }

    /** The quick sold-out switch, deliberately apart from the full edit. */
    @Operation(operationId = "setMenuItemAvailability", summary = "Bật/tắt hết món", description = "Công tắc nhanh `AVAILABLE`/`SOLD_OUT`, tách khỏi sửa món. Bật lại không khôi phục tồn kho.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @PatchMapping("/menu-items/{id}/status")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView setStatus(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MenuRequests.ItemStatus request) {
        return menu.setItemStatus(principal, id, request);
    }

    /** The complete list of option groups this dish offers, in order. */
    @Operation(operationId = "setMenuItemOptionGroups", summary = "Gắn nhóm lựa chọn cho món", description = "Danh sách đầy đủ nhóm của món theo thứ tự (tối đa 10, không trùng).")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @ApiError(status = 404, code = "OPTION_GROUP_NOT_FOUND", when = "Có nhóm không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "OPTION_GROUP_INVALID", when = "Một nhóm bị gắn hai lần.")
    @PutMapping("/menu-items/{id}/option-groups")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView setOptionGroups(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody OptionRequests.ItemGroups request) {
        return menu.setItemOptionGroups(principal, id, request);
    }

    @Operation(operationId = "uploadMenuItemPhoto", summary = "Tải ảnh món", description = "Ảnh JPG, PNG hoặc WebP, tối đa 5 MB, gửi dạng multipart trường `file`; thay ảnh cũ.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "FILE_REQUIRED", when = "Không có tệp trong trường `file`.")
    @ApiError(status = 400, code = "FILE_UNREADABLE", when = "Không đọc được tệp.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Tệp vượt giới hạn dung lượng.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp, không theo tên).")
    @PutMapping(path = "/menu-items/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView setPhoto(CurrentPrincipal principal, @PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return menu.setPhoto(principal, id, file);
    }

    @Operation(operationId = "removeMenuItemPhoto", summary = "Gỡ ảnh món", description = "Món trở lại ô ảnh trống; tệp cũ được xoá sau khi lưu.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "MENU_ITEM_NOT_FOUND", when = "Món không tồn tại, đã xoá hoặc thuộc quán khác.")
    @DeleteMapping("/menu-items/{id}/photo")
    @PreAuthorize("hasAuthority('vendor:write')")
    MenuView removePhoto(CurrentPrincipal principal, @PathVariable UUID id) {
        return menu.removePhoto(principal, id);
    }
}
