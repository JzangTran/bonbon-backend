package com.bonbon.backend.marketplace.category.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ModerationRequests {

    private ModerationRequests() {
    }

    @Schema(name = "FlagDishCategoryRequest")
    public record Flag(
            @Schema(description = "Vì sao ngành hiện tại sai; quán thấy lý do này.") @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Ngành đúng theo quản trị viên (bắt buộc, là ngành lá): hệ thống áp dụng nó nếu quán không sửa kịp.") @NotNull UUID suggestedCategoryId,
            @Schema(description = "Hạn quán tự sửa; bỏ trống là `category.fix_deadline_hours` (24) giờ kể từ bây giờ. Tối đa 7 ngày.") Instant deadlineAt) {
    }

    @Schema(name = "ReclassifyDishRequest", description = "Đổi ngành món ngay khi rõ ràng, không có hạn.")
    public record Reclassify(@NotNull UUID categoryId, @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "DecideCategoryAppealRequest")
    public record Decide(
            @Schema(description = "ACCEPT: món chuyển sang ngành quán xin (hoặc `categoryId` nếu khác). REJECT: ngành do hệ thống đặt giữ nguyên.")
            @NotNull @Pattern(regexp = "ACCEPT|REJECT", message = "Quyết định phải là ACCEPT hoặc REJECT.") String decision,
            @Schema(description = "Chỉ khi ACCEPT mà chấp nhận ngành khác với ngành quán xin.") UUID categoryId,
            @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "ResolveDishCategoryRequest")
    public record Resolve(@NotNull UUID categoryId) {
    }

    @Schema(name = "AppealCategoryCorrectionRequest")
    public record Appeal(@NotNull UUID categoryId, @NotBlank @Size(max = 500) String note) {
    }
}
