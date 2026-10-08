package com.bonbon.backend.settlement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public final class SettlementViews {

    private SettlementViews() {
    }

    @Schema(name = "SettlementOverview", description = "Tổng quan đối soát: số liệu toàn nền tảng và bảng các quán.")
    public record Overview(Totals totals, ShopPage shops) {
    }

    @Schema(name = "SettlementTotals")
    public record Totals(
            @Schema(description = "Số quán đã có bút toán.") long shops,
            @Schema(description = "Số đơn đã ghi sổ (đã giao, hoặc online khách không nhận).") long orders,
            @Schema(description = "Giá trị món sau giảm giá của các đơn đó (không gồm phí giao).") long foodValue,
            @Schema(description = "Hoa hồng đã tính, đã gồm VAT.") long commission,
            @Schema(description = "Phần hoa hồng ròng (chưa VAT).") long commissionNet,
            @Schema(description = "Phần VAT trong hoa hồng.") long commissionVat,
            @Schema(description = "Tỷ lệ hoa hồng thực tế bình quân: hoa hồng chia giá trị món, phần trăm.") BigDecimal effectiveRate,
            @Schema(description = "Tiền nền tảng đang giữ hộ và còn nợ các quán (tổng số dư dương, chủ yếu từ đơn online).") long owedToShops,
            @Schema(description = "Tiền các quán còn nợ nền tảng (tổng số dư âm, chủ yếu hoa hồng đơn COD).") long owedByShops) {
    }

    @Schema(name = "SettlementShopRow")
    public record ShopRow(
            UUID vendorId,
            String name,
            @Schema(description = "Dương: nền tảng nợ quán. Âm: quán nợ nền tảng.") long balance,
            @Schema(description = "Số tiền chi trả được ngay: số dư dương trừ phần giữ cho khiếu nại (hiện bằng 0).") long payable,
            @Schema(description = "Số tiền quán đang nợ (số dư âm đổi dấu); 0 nếu không nợ.") long owed,
            long orders,
            long foodValue,
            long commission,
            @Schema(description = "Hoa hồng chia giá trị món, phần trăm; vắng mặt khi chưa có đơn.") BigDecimal effectiveRate,
            @Schema(description = "Tỷ lệ thực tế thấp hơn hẳn mức chung: dấu hiệu món xếp sai ngành rẻ hơn.") boolean lowRate,
            @Schema(description = "Lần chi trả gần nhất.") Instant lastPayoutAt,
            @Schema(description = "Giai đoạn nợ hoa hồng quá hạn: NONE, OVERDUE, RESTRICTED, PAUSED hoặc REVIEW (chờ quản trị viên xem xét khoá quán).") String commissionStage) {
    }

    @Schema(name = "SettlementShopPage")
    public record ShopPage(List<ShopRow> items, int page, int size, long total) {
    }

    @Schema(name = "SettlementVendor")
    public record Vendor(UUID vendorId, String name, long balance, long payable, long heldForCases, long owed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "LedgerEntry")
    public record Entry(
            UUID id,
            @Schema(description = "ONLINE_EARNING, COD_COMMISSION, PAYOUT, COLLECTION, ADJUSTMENT, CASE_REFUND, CASE_COMMISSION_REVERSAL hoặc TAX_WITHHOLDING.") String type,
            @Schema(description = "Có dấu: dương là nền tảng nợ quán thêm, âm là quán nợ thêm.") int amount,
            UUID orderId,
            @Schema(description = "Mã đơn hiển thị.") Long orderNumber,
            @Schema(description = "Mã giao dịch ngân hàng của khoản chi trả hoặc thu.") String reference,
            String note,
            String actedByType,
            UUID actedById,
            Instant createdAt) {
    }

    @Schema(name = "LedgerPage")
    public record LedgerPage(List<Entry> items, int page, int size, long total, long balance) {
    }

    @Schema(name = "SettlementStatement", description = "Sao kê theo kỳ, tính từ sổ cái nên khớp với tổng quan.")
    public record Statement(String granularity, long openingBalance, long closingBalance, List<Period> periods) {
    }

    @Schema(name = "SettlementPeriod")
    public record Period(
            @Schema(description = "Ngày đầu kỳ, giờ Việt Nam (thứ Hai với kỳ tuần).") LocalDate start,
            @Schema(description = "Số đơn ghi sổ trong kỳ.") long orders,
            long onlineOrders,
            long cashOrders,
            @Schema(description = "Tiền món trước giảm giá.") long foodValue,
            long discounts,
            long deliveryFees,
            @Schema(description = "Hoa hồng đã gồm VAT.") long commission,
            long commissionNet,
            long commissionVat,
            @Schema(description = "Quán đã nhận (chi trả).") long payouts,
            @Schema(description = "Quán đã trả nền tảng (thu hoa hồng).") long collections,
            long adjustments,
            @Schema(description = "Hoàn tiền, hoàn hoa hồng theo khiếu nại và khấu trừ thuế.") long otherEntries,
            @Schema(description = "Tổng thay đổi số dư trong kỳ.") long change,
            long closingBalance) {
    }
}
