package com.bonbon.backend.merchantapproval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Shops that asked to close, and what still stands in the way (flows/merchant/close-shop.md). */
public final class ClosureDtos {

    private ClosureDtos() {
    }

    @Schema(name = "ClosureBlockers", description = "Những gì còn chặn việc đóng. Cửa hàng chỉ thành CLOSED khi tất cả đều bằng 0.")
    public record Blockers(
            @Schema(description = "Đơn đang làm: chờ quán nhận, đã nhận, đang chuẩn bị, đang giao.") long ordersInProgress,
            @Schema(description = "Khiếu nại còn chờ quán, khách hoặc quản trị viên.") long openCases,
            @Schema(description = "Kháng nghị điểm phạt còn chờ quản trị viên.") long pendingAppeals,
            @Schema(description = "Tiền đang bị giữ cho các khiếu nại chưa quyết.") long heldForCases,
            @Schema(description = "Số dư sổ cái: dương là nền tảng nợ quán (chi trả), âm là quán nợ nền tảng (thu hoặc xoá bằng bút toán điều chỉnh kèm lý do).") long balance,
            @Schema(description = "Không còn gì chặn.") boolean ready) {
    }

    @Schema(name = "ClosingShopRow")
    public record Row(UUID vendorId, String name, String reason, String reasonText, Instant requestedAt, Blockers blockers) {
    }

    @Schema(name = "ClosingShopList", description = "Cửa hàng đang đóng, yêu cầu cũ nhất trước.")
    public record Rows(List<Row> items) {
    }
}
