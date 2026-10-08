package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.CaseRequests;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import com.bonbon.backend.shopperformance.service.ShopCaseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_ORDER_CASES, description = "Khiếu nại của khách về đơn đã giao. Quán có 12 giờ để chấp nhận (khách được hoàn tiền và quán chịu khoản đó) hoặc phản đối (quản trị viên quyết). Không trả lời kịp cũng chuyển cho quản trị viên. Chỉ thấy khiếu nại của chính cửa hàng mình.")
@RestController
@RequestMapping("/api/merchant/order-cases")
@Validated
class MerchantOrderCaseController {

    private final ShopCaseService cases;

    MerchantOrderCaseController(ShopCaseService cases) {
        this.cases = cases;
    }

    @Operation(operationId = "listMyOrderCases", summary = "Danh sách khiếu nại của quán", description = "Khiếu nại đang chờ quán trả lời xếp đầu, rồi đang chờ quản trị viên, rồi đã xử lý; trong mỗi nhóm mới nhất trước. `status` lọc theo một trạng thái. `shopBears` là số tiền quán phải chịu nếu khiếu nại được chấp nhận.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải AWAITING_SHOP, AWAITING_CUSTOMER, OPEN, UPHELD hoặc DISMISSED.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    CaseViews.Page list(CurrentPrincipal principal, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return cases.list(principal, status, page, size);
    }

    @Operation(operationId = "getMyOrderCase", summary = "Chi tiết khiếu nại", description = "Các dòng món bị báo, ảnh khách gửi (liên kết ngắn hạn), ghi chú của khách, số tiền hoàn và số quán phải chịu, hạn trả lời.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại này ở cửa hàng của người gọi.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:read')")
    CaseViews.Case get(CurrentPrincipal principal, @PathVariable UUID id) {
        return cases.get(principal, id);
    }

    @Operation(operationId = "acceptOrderCase", summary = "Chấp nhận khiếu nại", description = "Khách được hoàn tiền ngay (qua MoMo cho đơn online, chuyển khoản cho đơn tiền mặt) và quán chịu khoản đó trong sổ cái: tiền hoàn ghi nợ, phần hoa hồng của số tiền này được hoàn lại cho quán. Phần tiền đang giữ được nhả. Không hoàn tác được.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "CASE_ALREADY_ANSWERED", when = "Khiếu nại không còn chờ quán trả lời (đã trả lời, quá hạn hoặc đã được quyết).")
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('order:write')")
    CaseViews.Case accept(CurrentPrincipal principal, @PathVariable UUID id) {
        return cases.accept(principal, id);
    }

    @Operation(operationId = "disputeOrderCase", summary = "Phản đối khiếu nại", description = "Chuyển cho quản trị viên quyết định, kèm lý do của quán. Trong lúc chờ, số tiền liên quan vẫn được giữ lại khỏi số có thể chi trả.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại này ở cửa hàng của người gọi.")
    @ApiError(status = 409, code = "CASE_ALREADY_ANSWERED", when = "Khiếu nại không còn chờ quán trả lời (đã trả lời, quá hạn hoặc đã được quyết).")
    @PostMapping("/{id}/dispute")
    @PreAuthorize("hasAuthority('order:write')")
    CaseViews.Case dispute(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CaseRequests.Dispute request) {
        return cases.dispute(principal, id, request.note());
    }
}
