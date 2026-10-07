package com.bonbon.backend.settlement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public final class CommissionViews {

    private CommissionViews() {
    }

    @Schema(name = "CommissionRates", description = "Tỷ lệ hoa hồng đang áp dụng: mặc định toàn nền tảng và từng ngành.")
    public record Rates(
            @Schema(description = "Tỷ lệ mặc định, dùng cho ngành không có tỷ lệ riêng và không có ngành cha nào có.") BigDecimal defaultRate,
            @Schema(description = "Mức tối đa cho phép khi đặt tỷ lệ.") BigDecimal maxRate,
            @Schema(description = "VAT (phần trăm) nằm trong tỷ lệ hoa hồng; phần hoa hồng ròng là tỷ lệ chia cho 1 + VAT.") BigDecimal vatPercent,
            List<CategoryRate> categories) {
    }

    /** One category as the commission screen shows it; parents come before children. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CategoryCommissionRate")
    public record CategoryRate(
            UUID id,
            UUID parentId,
            int level,
            String name,
            boolean active,
            @Schema(description = "Tỷ lệ riêng của ngành; vắng mặt khi ngành kế thừa.") BigDecimal ownRate,
            @Schema(description = "Tỷ lệ thực tế áp dụng cho món của ngành này.") BigDecimal effectiveRate,
            @Schema(description = "OWN (tỷ lệ riêng), ANCESTOR (kế thừa từ ngành cha) hoặc DEFAULT (tỷ lệ mặc định).") String source,
            @Schema(description = "Tên ngành mà tỷ lệ được kế thừa từ đó; vắng mặt khi là OWN hoặc DEFAULT.") String sourceName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CommissionRateChange", description = "Một lần đổi tỷ lệ. Không bao giờ ghi đè: luôn thêm dòng mới.")
    public record Change(
            UUID id,
            @Schema(description = "DEFAULT hoặc CATEGORY.") String scope,
            UUID categoryId,
            @Schema(description = "Tên ngành lúc đổi.") String categoryName,
            @Schema(description = "Tỷ lệ mới; vắng mặt khi ngành quay lại kế thừa.") BigDecimal rate,
            @Schema(description = "Tỷ lệ trước đó; vắng mặt khi ngành chưa có tỷ lệ riêng.") BigDecimal previousRate,
            @Schema(description = "Từ lúc này đơn đặt mới dùng tỷ lệ mới.") Instant effectiveFrom,
            String actedByType,
            UUID actedById) {
    }

    @Schema(name = "CommissionHistoryPage")
    public record History(List<Change> items, int page, int size, long total) {
    }
}
