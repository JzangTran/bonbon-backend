package com.bonbon.backend.settlement.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DebtRequests {

    private DebtRequests() {
    }

    @Schema(name = "ExtendStatementRequest")
    public record Extend(
            @Schema(description = "Hạn mới: sau hạn hiện tại và không quá 30 ngày kể từ bây giờ.") @NotNull Instant dueAt,
            @Schema(description = "Lý do gia hạn, được ghi lại.") @NotBlank @Size(max = 500) String reason) {
    }
}
