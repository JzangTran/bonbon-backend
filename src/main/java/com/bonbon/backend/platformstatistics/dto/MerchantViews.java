package com.bonbon.backend.platformstatistics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class MerchantViews {

    private MerchantViews() {
    }

    @Schema(name = "MerchantDirectoryRow")
    public record Row(
            UUID vendorId,
            String name,
            String ward,
            String province,
            @Schema(description = "PENDING, APPROVED, REJECTED, SUSPENDED hoặc CLOSED.") String status,
            BigDecimal deliveryRadiusKm,
            @Schema(description = "Điểm trung bình, rỗng khi chưa có đánh giá.") Double ratingAverage,
            int ratingCount,
            @Schema(description = "Số đơn đã giao từ trước đến nay.") long deliveredOrders,
            @Schema(description = "Tổng điểm phạt còn hiệu lực.") int activePoints,
            @Schema(description = "Đang bị hạn chế hiển thị vì điểm phạt.") boolean performanceRestricted,
            @Schema(description = "Giai đoạn nợ hoa hồng: NONE, OVERDUE, RESTRICTED, PAUSED hoặc REVIEW.") String commissionStage,
            @Schema(description = "Có lịch đình chỉ chưa áp dụng hoặc đang bị đình chỉ: SCHEDULED, APPLIED; rỗng nếu không.") String suspension,
            Instant createdAt) {
    }

    @Schema(name = "MerchantDirectoryPage")
    public record Page(List<Row> items, int page, int size, long total) {
    }

    @Schema(name = "CoverageShop")
    public record CoverageShop(
            UUID vendorId,
            String name,
            String address,
            String ward,
            String province,
            double lat,
            double lng,
            BigDecimal radiusKm,
            @Schema(description = "Số đơn đã giao: dấu hiệu hoạt động, không phải bộ lọc phạm vi.") long deliveredOrders,
            @Schema(description = "Bán kính trung vị của các quán cùng tỉnh hoặc thành.") BigDecimal provinceMedianRadiusKm,
            @Schema(description = "Bán kính lớn hơn `outlierFactor` lần trung vị cùng tỉnh: chỉ để quản trị viên xem kỹ, không bị hạn chế tự động.") boolean outlier) {
    }

    @Schema(name = "AreaCoverage", description = "Vùng phủ thực tế do bán kính giao riêng của từng quán tạo thành. Chỉ đọc.")
    public record Coverage(double outlierFactor, List<CoverageShop> items) {
    }
}
