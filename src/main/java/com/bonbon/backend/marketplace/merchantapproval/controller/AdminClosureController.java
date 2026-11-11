package com.bonbon.backend.marketplace.merchantapproval.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.marketplace.merchantapproval.dto.ClosureDtos;
import com.bonbon.backend.marketplace.merchantapproval.service.ShopClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_SHOP_REVIEW)
@RestController
@RequestMapping("/api/admin/merchants")
class AdminClosureController {

    private final ShopClosureService closures;

    AdminClosureController(ShopClosureService closures) {
        this.closures = closures;
    }

    @Operation(operationId = "listClosingShops", summary = "Cửa hàng đang đóng", description = "Cửa hàng chủ quán đã xin đóng, yêu cầu cũ nhất trước, kèm lý do và những gì còn chặn: đơn đang làm, khiếu nại và kháng nghị chưa xong, tiền bị giữ, số dư sổ cái. Hệ thống tự đóng khi mọi thứ về 0. Số dương được xử lý bằng chi trả ở mục Đối soát; số âm được thu hoặc xoá bằng bút toán điều chỉnh kèm lý do (quyết định của nền tảng, không phải của quán).")
    @GetMapping("/closing")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    ClosureDtos.Rows closing() {
        return closures.closing();
    }

    @Operation(operationId = "finishClosingShop", summary = "Đóng cửa hàng ngay", description = "Chuyển cửa hàng từ `CLOSING` sang `CLOSED` khi không còn gì chặn. Còn gì chặn thì từ chối và trả danh sách ở `blockers`.")
    @ApiError(status = 409, code = "CLOSURE_BLOCKED", when = "Còn đơn đang làm, khiếu nại, kháng nghị, tiền bị giữ hoặc số dư khác 0; chi tiết ở `blockers`.")
    @ApiError(status = 409, code = "SHOP_NOT_CLOSING", when = "Cửa hàng không đang trong quá trình đóng.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có cửa hàng này.")
    @PostMapping("/{vendorId}/finish-closing")
    @PreAuthorize("hasAuthority('merchant-approval:decide')")
    ClosureDtos.Blockers finish(@PathVariable UUID vendorId) {
        return closures.finish(vendorId);
    }
}
