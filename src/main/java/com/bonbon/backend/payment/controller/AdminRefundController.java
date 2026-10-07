package com.bonbon.backend.payment.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.payment.dto.RefundRequests;
import com.bonbon.backend.payment.dto.RefundViews;
import com.bonbon.backend.payment.service.RefundDesk;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_REFUNDS, description = "Hàng đợi hoàn tiền bằng chuyển khoản: những khoản MoMo không hoàn được. Quản trị viên chuyển tiền trong ứng dụng ngân hàng rồi ghi mã giao dịch; không sửa được số tiền.")
@RestController
@RequestMapping("/api/admin/refunds")
@PreAuthorize("hasAuthority('refund:process')")
class AdminRefundController {

    private final RefundDesk desk;

    AdminRefundController(RefundDesk desk) {
        this.desk = desk;
    }

    @Operation(operationId = "listRefunds", summary = "Hàng đợi hoàn tiền", description = "Mặc định là hàng đợi chuyển khoản đang mở (`REQUESTED` chờ quản trị chuyển, `NEEDS_DESTINATION` chờ khách nhập tài khoản), cũ nhất trước. `status=COMPLETED` xem khoản đã xong, `ALL` xem mọi khoản hoàn kể cả hoàn qua MoMo (để đối soát cuối tháng). Mỗi dòng có số tiền, mã đơn, lý do, mã kết quả MoMo và tài khoản khách nhập (số đầy đủ).")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không thuộc REQUESTED, NEEDS_DESTINATION, COMPLETED, ALL.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    RefundViews.Page list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return desk.list(status, page, size);
    }

    @Operation(operationId = "completeRefund", summary = "Xác nhận đã chuyển khoản", description = "Sau khi chuyển `amount` tới tài khoản khách nhập. Ghi mã giao dịch ngân hàng (mỗi mã chỉ dùng một lần) và tuỳ chọn thời điểm chuyển. Khách được báo đã hoàn tiền. Hai quản trị viên không thể cùng xác nhận một khoản.")
    @ApiError(status = 404, code = "REFUND_NOT_FOUND", when = "Không có khoản hoàn với id này.")
    @ApiError(status = 409, code = "REFUND_NOT_PAYABLE", when = "Khoản hoàn không ở trạng thái chờ chuyển khoản (đã xử lý, chờ khách nhập tài khoản hoặc đang hoàn qua MoMo).")
    @ApiError(status = 409, code = "DUPLICATE_BANK_REFERENCE", when = "Mã giao dịch này đã dùng cho một khoản hoàn khác (nghi nhập trùng).")
    @PostMapping("/{id}/complete")
    RefundViews.Row complete(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody RefundRequests.Complete request) {
        return desk.complete(principal.id(), id, request);
    }

    @Operation(operationId = "failRefund", summary = "Báo chuyển khoản không thành công", description = "Tài khoản đóng hoặc sai số. Lý do được ghi lại, tài khoản đã nhập bị xoá và khách được yêu cầu nhập tài khoản khác (khoản hoàn quay về `NEEDS_DESTINATION`).")
    @ApiError(status = 404, code = "REFUND_NOT_FOUND", when = "Không có khoản hoàn với id này.")
    @ApiError(status = 409, code = "REFUND_NOT_PAYABLE", when = "Khoản hoàn không ở trạng thái chờ chuyển khoản.")
    @PostMapping("/{id}/fail")
    RefundViews.Row fail(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody RefundRequests.Fail request) {
        return desk.fail(principal.id(), id, request);
    }
}
