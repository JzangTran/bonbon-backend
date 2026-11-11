package com.bonbon.backend.marketplace.merchantapproval.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchantapproval.dto.ClosureDtos;
import com.bonbon.backend.marketplace.merchantapproval.service.ShopClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_SHOP, description = "Hồ sơ và cài đặt của cửa hàng: thông tin, giờ mở cửa, tạm ngưng nhận đơn.")
@RestController
@RequestMapping("/api/merchant/closure")
@PreAuthorize("hasAuthority('vendor:read')")
class MerchantClosureController {

    private final ShopClosureService closures;

    MerchantClosureController(ShopClosureService closures) {
        this.closures = closures;
    }

    @Operation(operationId = "getMyShopClosure", summary = "Tình trạng đóng cửa hàng", description = "Khi cửa hàng đang `CLOSING`: lý do đã chọn và những gì còn chặn việc đóng hẳn (đơn đang làm, khiếu nại, kháng nghị, tiền đang giữ, số dư sổ cái). Cửa hàng thành `CLOSED` khi tất cả đều xong.")
    @ApiError(status = 409, code = "SHOP_NOT_CLOSING", when = "Cửa hàng của người gọi không đang trong quá trình đóng.")
    @GetMapping
    ClosureDtos.Mine mine(CurrentPrincipal principal) {
        return closures.mine(principal);
    }
}
