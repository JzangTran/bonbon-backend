package com.bonbon.backend.settlement.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Commission a shop owes: the statements billed to it and where it stands (flows/settlement/collect-commission-debt.md). */
public final class DebtViews {

    private DebtViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CommissionStanding", description = "Tình trạng nợ hoa hồng của quán. Số tiền quá hạn luôn tính từ sổ cái.")
    public record Standing(
            @Schema(description = "NONE, OVERDUE (đã báo trước), RESTRICTED (hạn chế hiển thị), PAUSED (tạm ngưng nhận đơn) hoặc REVIEW (chờ quản trị viên xem xét; quán vẫn tạm ngưng).") String stage,
            @Schema(description = "Số quán đang nợ nền tảng (số dư âm đổi dấu), kể cả phần chưa đến hạn.") long owed,
            @Schema(description = "Phần đã quá hạn; 0 nghĩa là không có gì bị hạn chế.") long overdue,
            @Schema(description = "Thời điểm bắt đầu quá hạn của đợt hiện tại.") Instant overdueSince,
            @Schema(description = "Sớm nhất khi quán bị hạn chế hiển thị, luôn sau thông báo ít nhất 5 ngày.") Instant restrictAt,
            @Schema(description = "Sớm nhất khi quán bị tạm ngưng nhận đơn.") Instant pauseAt,
            @Schema(description = "Khi quán được chuyển cho quản trị viên xem xét (không bao giờ tự khoá).") Instant reviewAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "CommissionStatement", description = "Một sao kê hoa hồng quán phải trả.")
    public record Statement(
            UUID id,
            @Schema(description = "WEEKLY (chốt hằng tuần) hoặc LIMIT (lập ngay khi nợ vượt hạn mức).") String kind,
            @Schema(description = "Ngày bắt đầu kỳ, giờ Việt Nam.") LocalDate periodStart,
            @Schema(description = "Thời điểm chốt: số dư tại đây là số tiền sao kê.") Instant periodEnd,
            @Schema(description = "Số tiền sao kê yêu cầu quán trả (đã gồm các khoản nợ trước đó).") long amountDue,
            @Schema(description = "Phần còn thiếu sau các khoản có vào sổ sau thời điểm chốt (trả nợ cũ nhất trước).") long unpaid,
            Instant dueAt,
            @Schema(description = "OPEN, OVERDUE hoặc PAID.") String status,
            @Schema(description = "Quản trị viên đã gia hạn.") boolean extended,
            @Schema(description = "Lý do gia hạn.") String extensionReason) {
    }

    @Schema(name = "CommissionStatementList")
    public record Statements(Standing standing, List<Statement> items) {
    }
}
