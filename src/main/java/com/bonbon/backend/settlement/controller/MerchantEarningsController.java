package com.bonbon.backend.settlement.controller;

import java.time.LocalDate;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.settlement.dto.EarningsViews;
import com.bonbon.backend.settlement.dto.SettlementViews;
import com.bonbon.backend.settlement.service.EarningsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_EARNINGS, description = "Tiền của quán với nền tảng: số dư (ai nợ ai), sao kê theo kỳ và sổ cái từng khoản. Số liệu lấy từ cùng sổ cái với trang đối soát của quản trị nên luôn khớp.")
@RestController
@RequestMapping("/api/merchant/earnings")
@PreAuthorize("hasAuthority('earnings:read')")
class MerchantEarningsController {

    private final EarningsService earnings;

    MerchantEarningsController(EarningsService earnings) {
        this.earnings = earnings;
    }

    @Operation(operationId = "getMyEarnings", summary = "Số dư của quán", description = "Số dư dương nghĩa là nền tảng đang giữ tiền khách trả online và nợ quán; số dư âm nghĩa là quán nợ nền tảng hoa hồng của các đơn thu tiền mặt. Kèm số có thể chi trả ngay, phần giữ cho khiếu nại đang mở (hiện luôn 0), số quán đang nợ và lần chi trả gần nhất.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping
    EarningsViews.Summary summary(CurrentPrincipal principal) {
        return earnings.summary(principal);
    }

    @Operation(operationId = "getMyEarningsStatements", summary = "Sao kê theo kỳ", description = "Theo ngày, tuần (từ thứ Hai) hoặc tháng theo giờ Việt Nam, mặc định 90 ngày gần nhất theo tuần: số đơn, tiền món, giảm giá, phí giao, hoa hồng (gồm VAT, tách phần ròng và VAT), tiền đã nhận từ nền tảng, tiền đã trả nền tảng, điều chỉnh và số dư cuối kỳ. Dùng `from`/`to` của kỳ để xem các đơn đằng sau qua sổ cái.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_GRANULARITY", when = "`granularity` không phải day, week hoặc month.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`, hoặc khoảng quá 2 năm.")
    @GetMapping("/statements")
    SettlementViews.Statement statements(CurrentPrincipal principal, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String granularity) {
        return earnings.statement(principal, from, to, granularity);
    }

    @Operation(operationId = "getMyLedger", summary = "Sổ cái của quán", description = "Mới nhất trước. Khoản thuộc đơn có mã đơn, tiền món, giảm giá, phí giao và hoa hồng của đơn đó; khoản chi trả và thu có mã giao dịch ngân hàng. `type` và khoảng `from`–`to` (ngày, gồm cả hai đầu, giờ Việt Nam) giúp xem các đơn đằng sau một kỳ trong sao kê. Không hiện quản trị viên nào đã ghi khoản, chỉ hiện `source`.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_TYPE", when = "`type` không phải một loại bút toán.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/ledger")
    EarningsViews.Ledger ledger(CurrentPrincipal principal, @RequestParam(required = false) String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return earnings.ledger(principal, type, from, to, page, size);
    }
}
