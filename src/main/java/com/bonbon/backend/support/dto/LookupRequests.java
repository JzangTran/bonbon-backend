package com.bonbon.backend.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class LookupRequests {

    private LookupRequests() {
    }

    @Schema(name = "RevealContactRequest")
    public record Reveal(
            @Schema(description = "Lý do xem thông tin đầy đủ: DISPUTE (tranh chấp), DATA_REQUEST (yêu cầu về dữ liệu), SAFETY (an toàn) hoặc OTHER (khác, bắt buộc ghi chú).")
            @NotNull @Pattern(regexp = "DISPUTE|DATA_REQUEST|SAFETY|OTHER", message = "Lý do phải là DISPUTE, DATA_REQUEST, SAFETY hoặc OTHER.") String reason,
            @Schema(description = "Ghi chú; bắt buộc khi lý do là OTHER.") @Size(max = 300) String note) {
    }
}
