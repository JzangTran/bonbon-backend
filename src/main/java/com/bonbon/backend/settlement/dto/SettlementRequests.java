package com.bonbon.backend.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SettlementRequests {

    private SettlementRequests() {
    }

    @Schema(name = "RecordLedgerEntryRequest", description = "Một khoản tiền đã chuyển ngoài hệ thống, ghi lại vào sổ.")
    public record Entry(
            @Schema(description = "PAYOUT (chuyển cho quán), COLLECTION (nhận từ quán nợ hoa hồng) hoặc ADJUSTMENT (điều chỉnh có lý do).")
            @NotNull @Pattern(regexp = "PAYOUT|COLLECTION|ADJUSTMENT", message = "Loại phải là PAYOUT, COLLECTION hoặc ADJUSTMENT.") String type,
            @Schema(description = "Số tiền VND. PAYOUT và COLLECTION: số dương, hệ thống tự gán dấu. ADJUSTMENT: có dấu (dương cộng cho quán, âm trừ của quán), khác 0.")
            @NotNull Long amount,
            @Schema(description = "Mã giao dịch ngân hàng để đối chiếu sao kê; bắt buộc với PAYOUT và COLLECTION.") @Size(max = 100) String reference,
            @Schema(description = "Ghi chú; ADJUSTMENT bắt buộc có lý do.") @Size(max = 500) String note) {
    }
}
