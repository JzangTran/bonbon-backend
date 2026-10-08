package com.bonbon.backend.settlement.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.settlement.dto.DebtViews;
import com.bonbon.backend.settlement.service.CommissionDebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_EARNINGS, description = "Tiền của quán với nền tảng: số dư (ai nợ ai), sao kê theo kỳ và sổ cái từng khoản. Số liệu lấy từ cùng sổ cái với trang đối soát của quản trị nên luôn khớp.")
@RestController
@RequestMapping("/api/merchant/settlement")
@PreAuthorize("hasAuthority('earnings:read')")
class MerchantCommissionController {

    private final CommissionDebtService debt;

    MerchantCommissionController(CommissionDebtService debt) {
        this.debt = debt;
    }

    @Operation(operationId = "getMyCommissionStatements", summary = "Sao kê hoa hồng quán phải trả", description = "Hoa hồng của đơn thu tiền mặt là khoản quán nợ nền tảng. Mỗi tuần (thứ Hai, giờ Việt Nam) quán nợ từ 50.000 ₫ nhận một sao kê, hạn trả 7 ngày; nợ vượt hạn mức được lập sao kê ngay với hạn 3 ngày. Khoản có vào sổ trả sao kê cũ nhất trước. `standing` cho biết quán có đang quá hạn không và các mốc hạn chế (hiển thị, tạm ngưng nhận đơn), mỗi mốc sau thông báo ít nhất 5 ngày; trả hết phần quá hạn thì mọi hạn chế được gỡ ngay. Mới nhất trước.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping("/statements")
    DebtViews.Statements statements(CurrentPrincipal principal) {
        return debt.statementsFor(principal);
    }
}
