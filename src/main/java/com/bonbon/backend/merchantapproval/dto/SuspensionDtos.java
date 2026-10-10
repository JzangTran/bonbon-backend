package com.bonbon.backend.merchantapproval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Suspending a shop with the legal notice (flows/merchant-approval/suspend-seller.md). */
public final class SuspensionDtos {

    private SuspensionDtos() {
    }

    @Schema(name = "SuspendShopRequest")
    public record Suspend(
            @Schema(description = "Lý do đình chỉ; quán thấy lý do này ngay khi được báo.") @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Ngày hiệu lực, cách bây giờ ít nhất 5 ngày (`legal.seller_restriction_notice_days`); bỏ trống là đúng 5 ngày.") Instant effectiveAt,
            @Schema(description = "Chỉ đình chỉ ngay khi có yêu cầu của cơ quan có thẩm quyền: đặt true và ghi số hiệu, ngày của yêu cầu ở `authorityReference`. Khi đó không có `effectiveAt`.") Boolean immediate,
            @Schema(description = "Số hiệu và ngày của yêu cầu từ cơ quan có thẩm quyền; bắt buộc khi `immediate`.") @Size(max = 300) String authorityReference) {
    }

    @Schema(name = "EndSuspensionRequest")
    public record End(@Schema(description = "Lý do huỷ đình chỉ hoặc khôi phục quán, được ghi lại; quán thấy lý do này.") @NotBlank @Size(max = 500) String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ShopSuspension")
    public record Suspension(
            UUID id,
            @Schema(description = "SCHEDULED (có báo trước) hoặc IMMEDIATE (theo yêu cầu của cơ quan có thẩm quyền).") String kind,
            @Schema(description = "SCHEDULED (đang trong thời gian báo trước, quán vẫn hoạt động), APPLIED (đang bị đình chỉ), CANCELLED hoặc LIFTED.") String status,
            String reason,
            String authorityReference,
            Instant noticeSentAt,
            Instant effectiveAt,
            Instant createdAt,
            Instant appliedAt,
            Instant endedAt,
            String endReason) {
    }

    @Schema(name = "ShopSuspensionHistory")
    public record History(List<Suspension> items) {
    }

    /** What the seller sees of its own suspension; {@code status} NONE when there is nothing to tell. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MyShopSuspension", description = "Tình trạng đình chỉ của chính quán.")
    public record Mine(
            @Schema(description = "NONE, SCHEDULED (sắp bị đình chỉ, vẫn hoạt động đến `effectiveAt`) hoặc SUSPENDED (đang bị đình chỉ).") String status,
            String reason,
            Instant effectiveAt,
            Instant noticeSentAt) {
    }
}
