package com.bonbon.backend.insights.platformstatistics.controller;

import java.time.LocalDate;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.insights.platformstatistics.dto.PlatformStatsViews;
import com.bonbon.backend.insights.platformstatistics.service.PlatformStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_PLATFORM_STATS, description = "Số liệu toàn hệ thống cho quản trị viên: đơn, doanh thu, hoa hồng, cửa hàng và người dùng hoạt động, doanh thu theo cửa hàng, người dùng mới theo vai trò. Chỉ tính đơn đã giao, theo lúc giao, giờ Việt Nam, giống báo cáo của từng cửa hàng. Doanh thu là số khách đã trả (đo tăng trưởng); hoa hồng là thu nhập của nền tảng.")
@RestController
@RequestMapping("/api/admin/stats")
@PreAuthorize("hasAuthority('platform-stats:read')")
class AdminStatsController {

    private final PlatformStatisticsService statistics;

    AdminStatsController(PlatformStatisticsService statistics) {
        this.statistics = statistics;
    }

    @Operation(operationId = "getPlatformOverview", summary = "Tổng quan hệ thống", description = "Số đơn đã giao, doanh thu gộp, hoa hồng, số cửa hàng và khách hoạt động trong khoảng `from`–`to` (mặc định 30 ngày gần nhất, gồm cả hai đầu), chia theo `granularity`: `day` (mặc định), `week` (từ thứ Hai) hoặc `month`. \"Hoạt động\" là có ít nhất một đơn đã giao trong khoảng. Trả mọi kỳ, kỳ không có đơn là 0.")
    @ApiError(status = 400, code = "INVALID_GRANULARITY", when = "`granularity` không phải day, week hoặc month.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "RANGE_TOO_LONG", when = "Quá nhiều kỳ (hơn 400); chia theo tuần hoặc tháng.")
    @GetMapping("/overview")
    PlatformStatsViews.Overview overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String granularity) {
        return statistics.overview(from, to, granularity);
    }

    @Operation(operationId = "getRevenueByMerchant", summary = "Doanh thu theo cửa hàng", description = "Mỗi cửa hàng có đơn đã giao trong khoảng một dòng: số đơn, doanh thu gộp, hoa hồng. `sort` là `revenue` (mặc định), `orders`, `commission` hoặc `name`; `q` tìm theo tên, không phân biệt hoa thường và dấu. Khớp với báo cáo doanh thu của chính cửa hàng trong cùng khoảng.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "INVALID_SORT", when = "`sort` không hợp lệ.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/revenue-by-merchant")
    PlatformStatsViews.MerchantPage revenueByMerchant(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return statistics.revenueByMerchant(from, to, sort, q, page, size);
    }

    @Operation(operationId = "getUserGrowth", summary = "Người dùng mới theo vai trò", description = "Số vai trò khách và người bán mới được thêm trong khoảng (theo ngày vai trò được thêm, không phải ngày tạo tài khoản), chia theo `granularity`. Mặc định 30 ngày gần nhất.")
    @ApiError(status = 400, code = "INVALID_GRANULARITY", when = "`granularity` không phải day, week hoặc month.")
    @ApiError(status = 400, code = "INVALID_RANGE", when = "`from` sau `to`.")
    @ApiError(status = 400, code = "RANGE_TOO_LONG", when = "Quá nhiều kỳ (hơn 400); chia theo tuần hoặc tháng.")
    @GetMapping("/user-growth")
    PlatformStatsViews.UserGrowth userGrowth(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) String granularity) {
        return statistics.userGrowth(from, to, granularity);
    }
}
