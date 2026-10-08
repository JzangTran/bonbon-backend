package com.bonbon.backend.shopperformance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** The administrator's side of penalty points (flows/shop-performance/manage-penalties.md). */
public final class PenaltyViews {

    private PenaltyViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PenaltyShopRow")
    public record ShopRow(
            UUID vendorId,
            String name,
            int activePoints,
            @Schema(description = "NONE, WARNING, RESTRICTION_SCHEDULED hoặc RESTRICTED.") String consequence,
            Instant restrictionStartsAt,
            @Schema(description = "Từ 6 điểm: nên xem xét đình chỉ (không bao giờ tự động).") boolean reviewFlagged,
            @Schema(description = "Số kháng nghị đang chờ quyết.") long pendingAppeals) {
    }

    @Schema(name = "PenaltyShopPage")
    public record ShopPage(List<ShopRow> items, int page, int size, long total) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PenaltyRecord")
    public record Penalty(
            UUID id,
            int points,
            String source,
            LocalDate weekStart,
            Integer finishedOrders,
            Integer faultOrders,
            String reason,
            @Schema(description = "ACTIVE hoặc WAIVED.") String status,
            Instant issuedAt,
            Instant expiresAt,
            @Schema(description = "Đã hết hạn 90 ngày (không còn tính điểm).") boolean expired,
            @Schema(description = "PENDING, ACCEPTED hoặc REJECTED; vắng mặt khi chưa kháng nghị.") String appealStatus,
            String appealReason,
            Instant appealedAt,
            String appealDecisionReason,
            String decisionReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ShopPenaltyHistory")
    public record History(
            UUID vendorId,
            String name,
            int activePoints,
            String consequence,
            Instant restrictionStartsAt,
            boolean reviewFlagged,
            List<Penalty> penalties) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "PenaltyAppealRow")
    public record Appeal(
            UUID penaltyId,
            UUID vendorId,
            String vendorName,
            int points,
            LocalDate weekStart,
            Integer finishedOrders,
            Integer faultOrders,
            Instant issuedAt,
            String appealReason,
            Instant appealedAt) {
    }

    @Schema(name = "PenaltyAppealPage")
    public record AppealPage(List<Appeal> items, int page, int size, long total) {
    }
}
