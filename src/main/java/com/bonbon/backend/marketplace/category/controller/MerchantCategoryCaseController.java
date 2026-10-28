package com.bonbon.backend.marketplace.category.controller;

import java.util.UUID;

import com.bonbon.backend.marketplace.category.dto.ModerationRequests;
import com.bonbon.backend.marketplace.category.dto.ModerationViews;
import com.bonbon.backend.marketplace.category.service.DishCategoryModerationService;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_CATEGORY_CASES, description = "Món của quán bị đánh dấu sai ngành hàng: sửa ngành trước hạn, hoặc nếu hệ thống đã tự sửa thì xác nhận hoặc kháng nghị trong 7 ngày. Cũng là lịch sử vi phạm của quán. Chỉ thấy các vụ của cửa hàng mình.")
@RestController
@RequestMapping("/api/merchant/category-cases")
@Validated
class MerchantCategoryCaseController {

    private final DishCategoryModerationService moderation;

    MerchantCategoryCaseController(DishCategoryModerationService moderation) {
        this.moderation = moderation;
    }

    @Operation(operationId = "listMyCategoryCases", summary = "Các vụ sai ngành của quán", description = "Mới nhất trước, lọc theo `status` (RESTRICTED và AUTO_CORRECTED là các vụ còn mở; còn lại là lịch sử). Mỗi vụ có lý do, ngành gợi ý, hạn sửa và hạn xác nhận hoặc kháng nghị.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng đang hoạt động.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không hợp lệ.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping
    @PreAuthorize("hasAuthority('vendor:read')")
    ModerationViews.Page list(CurrentPrincipal principal, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return moderation.mine(principal, status, page, size);
    }

    @Operation(operationId = "getMyCategoryCase", summary = "Chi tiết một vụ", description = "Vụ của cửa hàng người gọi.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng đang hoạt động.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('vendor:read')")
    ModerationViews.Case get(CurrentPrincipal principal, @PathVariable UUID id) {
        return moderation.mineOne(principal, id);
    }

    @Operation(operationId = "resolveMyCategoryCase", summary = "Sửa ngành của món", description = "Chọn ngành lá đúng (nên theo gợi ý). Vụ đóng (RESOLVED) và món hiện lại trong tìm kiếm, kể cả khi chọn ngành khác gợi ý; nếu quản trị viên vẫn thấy sai thì họ đánh dấu lại thành vụ mới. Áp dụng cho đơn đặt sau đó.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "CASE_NOT_OPEN", when = "Vụ không còn chờ quán sửa (đã xử lý, đã kháng nghị hoặc hết hạn); `status` cho biết trạng thái hiện tại.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành không phải ngành lá đang hoạt động.")
    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAuthority('vendor:write')")
    ModerationViews.Case resolve(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ModerationRequests.Resolve request) {
        return moderation.resolve(principal, id, request.categoryId());
    }

    @Operation(operationId = "confirmMyCategoryCase", summary = "Đồng ý với ngành hệ thống đã sửa", description = "Chỉ trong thời hạn sau khi hệ thống tự sửa. Vụ đóng (CONFIRMED).")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "CASE_NOT_OPEN", when = "Vụ không ở trạng thái đã tự sửa, hoặc đã hết hạn.")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('vendor:write')")
    ModerationViews.Case confirm(CurrentPrincipal principal, @PathVariable UUID id) {
        return moderation.confirm(principal, id);
    }

    @Operation(operationId = "appealMyCategoryCase", summary = "Kháng nghị ngành hệ thống đã sửa", description = "Chọn ngành quán cho là đúng và ghi giải thích. Mỗi vụ một lần, chỉ trong thời hạn. Trong lúc chờ, món giữ ngành do hệ thống đặt; quản trị viên quyết định, thường trong 3–5 ngày làm việc.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "CASE_NOT_OPEN", when = "Vụ không ở trạng thái đã tự sửa, đã hết hạn hoặc đã kháng nghị.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành không phải ngành lá đang hoạt động.")
    @PostMapping("/{id}/appeal")
    @PreAuthorize("hasAuthority('vendor:write')")
    ModerationViews.Case appeal(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ModerationRequests.Appeal request) {
        return moderation.appeal(principal, id, request);
    }
}
