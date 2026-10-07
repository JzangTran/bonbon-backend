package com.bonbon.backend.statistics.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class StatsViews {

    private StatsViews() {
    }

    @Schema(name = "RevenueReport", description = "Doanh thu gộp của các đơn đã giao trong khoảng thời gian, theo từng ngày, tuần hoặc tháng.")
    public record Revenue(
            LocalDate from,
            LocalDate to,
            @Schema(description = "day, week (bắt đầu từ thứ Hai) hoặc month, theo giờ Việt Nam.") String granularity,
            Totals totals,
            @Schema(description = "Mọi kỳ trong khoảng, kể cả kỳ không có đơn nào (0), cũ nhất trước: sẵn để vẽ biểu đồ.") List<Bucket> buckets) {
    }

    @Schema(name = "RevenueTotals")
    public record Totals(
            @Schema(description = "Số đơn đã giao.") long orders,
            @Schema(description = "Tổng khách đã trả, gồm phí giao hàng, chưa trừ hoa hồng.") long revenue,
            @Schema(description = "Giá trị đơn trung bình; 0 khi chưa có đơn.") long averageOrderValue) {
    }

    @Schema(name = "RevenueBucket")
    public record Bucket(
            @Schema(description = "Ngày đầu kỳ.") LocalDate start,
            long orders,
            long revenue,
            long averageOrderValue) {
    }

    @Schema(name = "TopDishes", description = "Món bán chạy: xếp theo số lượng bán của các đơn đã giao.")
    public record TopDishes(LocalDate from, LocalDate to, List<Dish> items) {
    }

    @Schema(name = "BestSellingDish")
    public record Dish(
            UUID menuItemId,
            @Schema(description = "Tên món lúc khách đặt (món đã đổi tên hoặc xoá vẫn hiện tên cũ).") String name,
            @Schema(description = "Số phần đã bán.") long quantity,
            @Schema(description = "Tiền món của các phần đó (gồm lựa chọn thêm, không gồm phí giao).") long revenue) {
    }
}
