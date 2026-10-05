package com.bonbon.backend.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReviewRequests {

    private ReviewRequests() {
    }

    @Schema(name = "ReviewRequest")
    public record Write(
            @Schema(description = "Số sao, 1–5.", example = "5") @Min(1) @Max(5) int rating,
            @Schema(description = "Nhận xét, tối đa 1000 ký tự; có thể bỏ trống.") @Size(max = 1000) String comment) {
    }

    @Schema(name = "ReviewModerationRequest")
    public record Hide(@Schema(description = "Lý do ẩn, bắt buộc (ghi vào nhật ký kiểm toán).") @NotBlank @Size(max = 300) String reason) {
    }
}
