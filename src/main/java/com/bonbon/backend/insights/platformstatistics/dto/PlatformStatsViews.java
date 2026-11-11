package com.bonbon.backend.insights.platformstatistics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class PlatformStatsViews {

    private PlatformStatsViews() {
    }

    @Schema(name = "PlatformOverview", description = "Số liệu toàn hệ thống của các đơn đã giao trong khoảng thời gian, theo ngày, tuần hoặc tháng.")
    public record Overview(
            LocalDate from,
            LocalDate to,
            @Schema(description = "day, week (bắt đầu từ thứ Hai) hoặc month, theo giờ Việt Nam.") String granularity,
            Totals totals,
            @Schema(description = "Mọi kỳ trong khoảng, kể cả kỳ không có đơn nào (0), cũ nhất trước: sẵn để vẽ biểu đồ.") List<Bucket> buckets) {
    }

    @Schema(name = "PlatformOverviewTotals")
    public record Totals(
            @Schema(description = "Số đơn đã giao.") long orders,
            @Schema(description = "Tổng khách đã trả, gồm phí giao, chưa trừ hoa hồng: đo tăng trưởng.") long grossRevenue,
            @Schema(description = "Tổng hoa hồng tính trên các đơn đó, chưa trừ phần hoàn lại do khiếu nại: đo thu nhập của nền tảng.") long commission,
            @Schema(description = "Số cửa hàng có ít nhất một đơn đã giao trong khoảng.") long activeShops,
            @Schema(description = "Số khách có ít nhất một đơn đã giao trong khoảng.") long activeCustomers,
            @Schema(description = "Số người bán có cửa hàng hoạt động (bằng số cửa hàng vì mỗi tài khoản bán hàng một cửa hàng).") long activeSellers) {
    }

    @Schema(name = "PlatformOverviewBucket")
    public record Bucket(@Schema(description = "Ngày đầu kỳ.") LocalDate start, long orders, long grossRevenue, long commission) {
    }

    @Schema(name = "MerchantRevenueRow")
    public record MerchantRow(UUID vendorId, String name, long orders, long grossRevenue, long commission) {
    }

    @Schema(name = "MerchantRevenuePage", description = "Doanh thu theo cửa hàng. Khớp đúng với báo cáo doanh thu của chính cửa hàng đó trong cùng khoảng.")
    public record MerchantPage(LocalDate from, LocalDate to, List<MerchantRow> items, int page, int size, long total) {
    }

    @Schema(name = "UserGrowthReport", description = "Vai trò mới được thêm cho tài khoản theo thời gian. Một người đăng ký làm khách rồi mở quán sau đó được tính một lần ở mỗi chuỗi, vào đúng ngày của nó.")
    public record UserGrowth(
            LocalDate from,
            LocalDate to,
            String granularity,
            long customers,
            long sellers,
            @Schema(description = "Mọi kỳ trong khoảng, kể cả kỳ không có ai (0).") List<GrowthBucket> buckets) {
    }

    @Schema(name = "UserGrowthBucket")
    public record GrowthBucket(LocalDate start, long customers, long sellers) {
    }
}
