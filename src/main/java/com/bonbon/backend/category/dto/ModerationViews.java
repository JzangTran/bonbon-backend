package com.bonbon.backend.category.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class ModerationViews {

    private ModerationViews() {
    }

    @Schema(name = "CategoryRef")
    public record CategoryRef(UUID id, String name) {
    }

    @Schema(name = "DishCategoryCase")
    public record Case(
            UUID id,
            UUID dishId,
            String dishName,
            UUID vendorId,
            String shopName,
            @Schema(description = "RESTRICTED (món bị ẩn khỏi tìm kiếm, chờ sửa), AUTO_CORRECTED (hệ thống đã đổi ngành theo gợi ý, còn thời gian xác nhận hoặc kháng nghị), APPEALED, RESOLVED (quán tự sửa hoặc quản trị đổi), CONFIRMED, EXPIRED, APPEAL_ACCEPTED hoặc APPEAL_REJECTED.") String status,
            String reason,
            CategoryRef originalCategory,
            CategoryRef suggestedCategory,
            @Schema(description = "Ngành quán muốn khi kháng nghị.") CategoryRef requestedCategory,
            @Schema(description = "Ngành món đang ở / kết thúc ở.") CategoryRef finalCategory,
            @Schema(description = "Hạn quán tự sửa; sau hạn hệ thống áp dụng gợi ý.") Instant deadlineAt,
            @Schema(description = "Hạn xác nhận hoặc kháng nghị sau khi hệ thống tự sửa.") Instant appealDeadlineAt,
            String appealNote,
            Instant openedAt,
            Instant appealedAt,
            Instant decidedAt,
            @Schema(description = "SYSTEM, ADMIN hoặc loại tài khoản của quán.") String decidedByType,
            String decisionReason) {
    }

    @Schema(name = "DishCategoryCasePage")
    public record Page(List<Case> items, int page, int size, long total) {
    }
}
