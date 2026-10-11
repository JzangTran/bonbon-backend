package com.bonbon.backend.merchantapproval.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.merchantapproval.dto.ReviewDtos;
import com.bonbon.backend.merchantapproval.service.MerchantDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_MERCHANTS)
@RestController
@RequestMapping("/api/admin/merchants")
@PreAuthorize("hasAuthority('merchant-approval:read')")
class AdminMerchantController {

    private final MerchantDetailService details;

    AdminMerchantController(MerchantDetailService details) {
        this.details = details;
    }

    @Operation(operationId = "getMerchantDetail", summary = "Chi tiết cửa hàng", description = "Hồ sơ của cửa hàng ở bất kỳ trạng thái nào đã gửi (không gồm bản nháp): thông tin quán, giao hàng, thuế, tài khoản nhận tiền dạng che, các quyết định duyệt, lịch sử đình chỉ và cảnh báo trùng (cùng mã số thuế, hoặc cùng họ tên và 4 số cuối giấy tờ). Cảnh báo chỉ để người duyệt xem, không tự từ chối. Giấy tờ định danh nằm ở endpoint riêng với quyền riêng và được ghi nhật ký mỗi lần xem.")
    @ApiError(status = 404, code = "SHOP_APPLICATION_NOT_FOUND", when = "Không có cửa hàng đã gửi hồ sơ với id này.")
    @GetMapping("/{vendorId}")
    ReviewDtos.MerchantDetail detail(@PathVariable UUID vendorId) {
        return details.detail(vendorId);
    }
}
