package com.bonbon.backend.shopperformance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PenaltyRequests {

    private PenaltyRequests() {
    }

    @Schema(name = "WaivePenaltyRequest")
    public record Waive(@Schema(description = "Lý do miễn điểm (ví dụ sự cố của nền tảng làm đơn thất bại); quán thấy lý do này.") @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "AddPenaltyRequest")
    public record Add(
            @Schema(description = "Số điểm cộng, từ 1 đến 3.") @NotNull @Min(1) @Max(3) Integer points,
            @Schema(description = "Lý do cộng điểm; quán thấy lý do này.") @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "AppealPenaltyRequest")
    public record Appeal(@Schema(description = "Vì sao quán cho rằng điểm này không đúng.") @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "DecidePenaltyAppealRequest")
    public record AppealDecision(
            @Schema(description = "ACCEPT (miễn điểm) hoặc REJECT (giữ nguyên).") @NotNull @Pattern(regexp = "ACCEPT|REJECT", message = "Quyết định phải là ACCEPT hoặc REJECT.") String decision,
            @Schema(description = "Lý do quyết định; quán thấy lý do này.") @NotBlank @Size(max = 500) String reason) {
    }
}
