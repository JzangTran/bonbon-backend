package com.bonbon.backend.merchant.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.OptionGroupsView;
import com.bonbon.backend.merchant.dto.OptionRequests;
import com.bonbon.backend.merchant.service.OptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The caller's own option groups. Every write answers with all groups so a client can replace its copy. */
@Tag(name = ApiTags.SELLER_OPTIONS, description = "Nhóm lựa chọn (cỡ ly, topping…) của quán, dùng lại cho nhiều món. Mọi thao tác ghi trả về toàn bộ nhóm.")
@RestController
@RequestMapping("/api/merchant")
class OptionController {

    private final OptionService optionService;

    OptionController(OptionService optionService) {
        this.optionService = optionService;
    }

    @Operation(operationId = "listOptionGroups", summary = "Danh sách nhóm lựa chọn", description = "Mỗi nhóm kèm các lựa chọn còn dùng và id các món đang gắn nhóm.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @GetMapping("/option-groups")
    @PreAuthorize("hasAuthority('vendor:read')")
    OptionGroupsView list(CurrentPrincipal principal) {
        return optionService.list(principal);
    }

    @Operation(operationId = "createOptionGroup", summary = "Thêm nhóm lựa chọn", description = "`min` 0 là tuỳ chọn, từ 1 là bắt buộc; `max` 1 là chọn một. Cần min ≤ max ≤ số lựa chọn, tối đa 30 lựa chọn; giá cộng thêm không âm.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "OPTION_GROUP_INVALID", when = "Vi phạm quy tắc min/max, số lựa chọn mặc định hoặc trạng thái.")
    @PostMapping("/option-groups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView create(CurrentPrincipal principal, @Valid @RequestBody OptionRequests.Group request) {
        return optionService.create(principal, request);
    }

    /** Replaces the group and its options; options left out are archived. */
    @Operation(operationId = "replaceOptionGroup", summary = "Sửa nhóm lựa chọn", description = "Ghi lại cả nhóm: lựa chọn có `id` được sửa, không có `id` được thêm, lựa chọn bị bỏ ra được lưu trữ.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "OPTION_GROUP_NOT_FOUND", when = "Nhóm không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 404, code = "OPTION_NOT_FOUND", when = "Có `id` lựa chọn không thuộc nhóm này.")
    @ApiError(status = 400, code = "OPTION_GROUP_INVALID", when = "Vi phạm quy tắc min/max.")
    @PutMapping("/option-groups/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody OptionRequests.Group request) {
        return optionService.update(principal, id, request);
    }

    /** Detaches the group from its dishes and archives it. */
    @Operation(operationId = "deleteOptionGroup", summary = "Xoá nhóm lựa chọn", description = "Gỡ nhóm khỏi mọi món rồi lưu trữ; đơn cũ không bị ảnh hưởng.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "OPTION_GROUP_NOT_FOUND", when = "Nhóm không tồn tại hoặc thuộc quán khác.")
    @DeleteMapping("/option-groups/{id}")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView delete(CurrentPrincipal principal, @PathVariable UUID id) {
        return optionService.delete(principal, id);
    }

    @Operation(operationId = "setOptionAvailability", summary = "Bật/tắt hết một lựa chọn", description = "Công tắc nhanh `AVAILABLE`/`SOLD_OUT` (ví dụ hết trân châu).")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "OPTION_NOT_FOUND", when = "Lựa chọn không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "OPTION_GROUP_INVALID", when = "Chỉ nhận `AVAILABLE` hoặc `SOLD_OUT`.")
    @PatchMapping("/options/{id}/status")
    @PreAuthorize("hasAuthority('vendor:write')")
    OptionGroupsView setStatus(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody OptionRequests.Status request) {
        return optionService.setOptionStatus(principal, id, request.status());
    }
}
