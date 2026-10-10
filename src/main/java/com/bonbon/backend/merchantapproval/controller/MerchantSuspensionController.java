package com.bonbon.backend.merchantapproval.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchantapproval.dto.SuspensionDtos;
import com.bonbon.backend.merchantapproval.service.SuspensionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_SHOP, description = "Hồ sơ và cài đặt của cửa hàng: thông tin, giờ mở cửa, tạm ngưng nhận đơn.")
@RestController
@RequestMapping("/api/merchant/suspension")
@PreAuthorize("hasAuthority('vendor:read')")
class MerchantSuspensionController {

    private final SuspensionService suspensions;

    MerchantSuspensionController(SuspensionService suspensions) {
        this.suspensions = suspensions;
    }

    @Operation(operationId = "getMyShopSuspension", summary = "Tình trạng đình chỉ của quán", description = "`NONE`, `SCHEDULED` (đã được báo, quán vẫn hoạt động đến `effectiveAt`) hoặc `SUSPENDED`, kèm lý do. Quán bị đình chỉ vẫn đăng nhập được để xem lý do, hoàn tất đơn đang làm và xem thu nhập.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping
    SuspensionDtos.Mine mine(CurrentPrincipal principal) {
        return suspensions.mine(principal);
    }
}
