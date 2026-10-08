package com.bonbon.backend.settlement.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a shop sees of its own money. The administrator's identity behind an entry is never shown. */
public final class EarningsViews {

    private EarningsViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "EarningsSummary", description = "Số dư hiện tại của quán với nền tảng.")
    public record Summary(
            @Schema(description = "Dương: nền tảng nợ quán (tiền khách trả online chưa chuyển cho quán). Âm: quán nợ nền tảng (hoa hồng đơn tiền mặt).") long balance,
            @Schema(description = "Số tiền nền tảng có thể chuyển cho quán ngay: số dư dương trừ phần giữ cho khiếu nại.") long payable,
            @Schema(description = "Tiền đang giữ lại vì khách khiếu nại đơn chưa có quyết định (hiện luôn 0).") long heldForCases,
            @Schema(description = "Số quán đang nợ nền tảng; 0 nếu không nợ.") long owed,
            @Schema(description = "Số tiền lần chi trả gần nhất.") Long lastPayoutAmount,
            @Schema(description = "Thời điểm lần chi trả gần nhất.") Instant lastPayoutAt) {
    }

    /** One line of the shop's ledger; order lines carry the figures behind them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "EarningsEntry")
    public record Entry(
            UUID id,
            @Schema(description = "ONLINE_EARNING, COD_COMMISSION, PAYOUT, COLLECTION, ADJUSTMENT, CASE_REFUND, CASE_COMMISSION_REVERSAL hoặc TAX_WITHHOLDING.") String type,
            @Schema(description = "Có dấu: dương là tăng số tiền nền tảng nợ quán, âm là giảm.") int amount,
            UUID orderId,
            @Schema(description = "Mã đơn hiển thị.") Long orderNumber,
            @Schema(description = "Tiền món của đơn trước giảm giá (khoản thuộc đơn).") Integer itemsTotal,
            Integer discount,
            Integer deliveryFee,
            @Schema(description = "Hoa hồng của đơn, đã gồm VAT.") Integer commission,
            @Schema(description = "Mã giao dịch ngân hàng của khoản chi trả hoặc thu.") String reference,
            String note,
            @Schema(description = "SYSTEM (tự ghi khi đơn kết thúc) hoặc PLATFORM (quản trị viên ghi tay).") String source,
            Instant createdAt) {
    }

    @Schema(name = "EarningsLedgerPage")
    public record Ledger(List<Entry> items, int page, int size, long total) {
    }
}
