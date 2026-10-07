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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The customer's side of a refund that has to go back by bank transfer. */
@Tag(name = ApiTags.CUSTOMER_ORDERS)
@RestController
@RequestMapping("/api/orders/{orderId}/refund-destination")
@PreAuthorize("hasAuthority('order:create')")
class RefundController {

    private final RefundDesk desk;

    RefundController(RefundDesk desk) {
        this.desk = desk;
    }

    @Operation(operationId = "setRefundDestination", summary = "Nhập tài khoản nhận hoàn tiền", description = "Khi hoàn tiền qua MoMo không thực hiện được, bonbon hoàn bằng chuyển khoản và cần số tài khoản của khách (`refund.needsDestination` trên đơn). Tài khoản chỉ lưu cho khoản hoàn này, số đầy đủ chỉ quản trị viên xử lý hoàn tiền xem được. Sau lần chuyển khoản thất bại, nhập lại được tài khoản khác.")
    @ApiError(status = 404, code = "REFUND_NOT_FOUND", when = "Đơn không phải của người gọi hoặc không có khoản hoàn nào chờ tài khoản.")
    @ApiError(status = 409, code = "REFUND_NOT_ACCEPTING_DESTINATION", when = "Khoản hoàn này không cần nhập tài khoản (đã nhập, đang hoàn qua MoMo hoặc đã xong).")
    @PutMapping
    RefundViews.Mine set(CurrentPrincipal principal, @PathVariable UUID orderId, @Valid @RequestBody RefundRequests.Destination request) {
        return desk.setDestination(principal.id(), orderId, request);
    }
}
