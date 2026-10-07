package com.bonbon.backend.statistics.controller;

import java.time.LocalDate;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.statistics.dto.StatsViews;
import com.bonbon.backend.statistics.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.SELLER_STATS, description = "Doanh số của quán: doanh thu theo thời gian và món bán chạy. Số liệu gộp (khách đã trả), không phải tiền quán được nhận: phần đó ở mục Thu nhập.")
@RestController
@RequestMapping("/api/merchant/stats")
@PreAuthorize("hasAuthority('stats:read')")
class MerchantStatsController {

    private final StatisticsService statistics;

    MerchantStatsController(StatisticsService statistics) {
        this.statistics = statistics;
    }

    @Operation(operationId = "getRevenueStats", summary = "Doanh thu theo thời gian", description = "Chỉ tính đơn đã giao (`DELIVERED`), theo lúc giao, giờ Việt Nam; gồm cả phí giao, chưa trừ hoa hồng. `from`/`to` là ngày, gồm cả hai đầu (mặc định 7 ngày gần nhất); `granularity` là `day` (mặc định), `week` (từ thứ Hai) hoặc `month`. Trả mọi kỳ trong khoảng, kỳ không có đơn là 0, kèm tổng số đơn, doanh thu và giá trị đơn trung bình.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_GRANULARITY", when = "`granularity` không phải day, week hoặc month.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "RANGE_TOO_LONG", when = "Quá nhiều kỳ (hơn 400); chia theo tuần hoặc tháng.")
    @GetMapping("/revenue")
    StatsViews.Revenue revenue(CurrentPrincipal principal, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String granularity) {
        return statistics.revenue(principal, from, to, granularity);
    }

    @Operation(operationId = "getBestSellingDishes", summary = "Món bán chạy", description = "Món xếp theo số phần đã bán của các đơn đã giao trong khoảng `from`–`to` (mặc định 30 ngày gần nhất), kèm tiền món của các phần đó. Dùng tên món lúc khách đặt nên món đã đổi tên hay xoá vẫn hiện.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "INVALID_LIMIT", when = "`limit` ngoài 1–50.")
    @GetMapping("/best-selling-dishes")
    StatsViews.TopDishes bestSelling(CurrentPrincipal principal, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(defaultValue = "10") int limit) {
        return statistics.topDishes(principal, from, to, limit);
    }
}
