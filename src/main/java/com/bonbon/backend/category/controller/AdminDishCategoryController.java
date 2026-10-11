package com.bonbon.backend.category.controller;

import java.util.UUID;

import com.bonbon.backend.category.dto.ModerationRequests;
import com.bonbon.backend.category.dto.ModerationViews;
import com.bonbon.backend.category.service.DishCategoryModerationService;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_DISH_CATEGORIES, description = "Kiểm duyệt ngành hàng của món. Hoa hồng tính theo ngành của món mà quán tự chọn, nên món xếp sai sang ngành rẻ hơn bị đánh dấu: bị ẩn khỏi tìm kiếm và lọc ngành (vẫn có trên thực đơn và vẫn đặt được) cho đến khi quán sửa; quá hạn hệ thống áp dụng ngành gợi ý, quán có 7 ngày để xác nhận hoặc kháng nghị, quản trị viên quyết định kháng nghị. Đổi ngành chỉ áp dụng cho đơn đặt sau đó.")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('dish:moderate')")
@Validated
class AdminDishCategoryController {

    private final DishCategoryModerationService moderation;

    AdminDishCategoryController(DishCategoryModerationService moderation) {
        this.moderation = moderation;
    }

    @Operation(operationId = "flagDishCategory", summary = "Đánh dấu món sai ngành", description = "Bắt buộc có lý do và ngành gợi ý (ngành lá). Hạn sửa mặc định 24 giờ (`category.fix_deadline_hours`), tối đa 7 ngày. Món bị ẩn khỏi tìm kiếm và lọc ngành ngay; quán được báo ngay. Mỗi món một vụ chưa đóng.")
    @ApiError(status = 404, code = "DISH_NOT_FOUND", when = "Không có món này.")
    @ApiError(status = 409, code = "DISH_ARCHIVED", when = "Món đã bị xoá khỏi thực đơn.")
    @ApiError(status = 409, code = "CASE_OPEN", when = "Món đang có một vụ chưa đóng.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành gợi ý không phải ngành lá đang hoạt động.")
    @ApiError(status = 400, code = "SAME_CATEGORY", when = "Ngành gợi ý trùng ngành hiện tại.")
    @ApiError(status = 400, code = "DEADLINE_IN_THE_PAST", when = "Hạn ở quá khứ.")
    @ApiError(status = 400, code = "DEADLINE_TOO_FAR", when = "Hạn quá 7 ngày.")
    @ApiResponse(responseCode = "201", description = "Đã đánh dấu.")
    @PostMapping("/dishes/{id}/flag")
    @ResponseStatus(HttpStatus.CREATED)
    ModerationViews.Case flag(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ModerationRequests.Flag request) {
        return moderation.flag(principal, id, request);
    }

    @Operation(operationId = "reclassifyDish", summary = "Đổi ngành món ngay", description = "Khi sai rõ ràng, không có hạn. Vụ đang chờ quán sửa hoặc đã tự sửa của món được đóng cùng (RESOLVED); quán được báo. Từ chối nếu quán đang kháng nghị: hãy quyết định kháng nghị. Trả về vụ đã đóng, hoặc rỗng nếu món không có vụ nào.")
    @ApiError(status = 404, code = "DISH_NOT_FOUND", when = "Không có món này.")
    @ApiError(status = 409, code = "APPEAL_PENDING", when = "Quán đang kháng nghị món này.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành không phải ngành lá đang hoạt động.")
    @PostMapping("/dishes/{id}/reclassify")
    ModerationViews.Case reclassify(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ModerationRequests.Reclassify request) {
        return moderation.reclassify(principal, id, request);
    }

    @Operation(operationId = "listDishCategoryCases", summary = "Danh sách vụ sai ngành", description = "Lọc theo `status` và `vendorId` (lịch sử vi phạm của một quán). `status=APPEALED` là hàng chờ kháng nghị, cũ nhất trước (đích 3–5 ngày làm việc); còn lại mới nhất trước.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không hợp lệ.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping("/category-cases")
    ModerationViews.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID vendorId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return moderation.list(status, vendorId, page, size);
    }

    @Operation(operationId = "getDishCategoryCase", summary = "Chi tiết một vụ", description = "Ngành gốc, ngành gợi ý, ngành quán xin, ngành cuối và các mốc thời gian.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này.")
    @GetMapping("/category-cases/{id}")
    ModerationViews.Case get(@PathVariable UUID id) {
        return moderation.get(id);
    }

    @Operation(operationId = "decideCategoryAppeal", summary = "Quyết định kháng nghị", description = "`ACCEPT`: món chuyển sang ngành quán xin (hoặc ngành khác nếu gửi `categoryId`, giải thích trong lý do); `REJECT`: ngành do hệ thống đặt giữ nguyên. Cần lý do; quán được báo. Chỉ áp dụng cho đơn đặt sau đó.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có vụ này.")
    @ApiError(status = 409, code = "CASE_NOT_APPEALED", when = "Vụ không có kháng nghị đang chờ.")
    @ApiError(status = 400, code = "CATEGORY_NOT_ASSIGNABLE", when = "Ngành chọn không phải ngành lá đang hoạt động.")
    @PostMapping("/category-cases/{id}/decide")
    ModerationViews.Case decide(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ModerationRequests.Decide request) {
        return moderation.decide(principal, id, request);
    }
}
