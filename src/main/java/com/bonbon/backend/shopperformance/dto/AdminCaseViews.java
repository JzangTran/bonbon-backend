package com.bonbon.backend.shopperformance.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** The administrator's side of order cases: the queue, one case with its evidence, and the decision. */
public final class AdminCaseViews {

    private AdminCaseViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "AdminOrderCaseSummary")
    public record Summary(
            UUID id,
            long orderNumber,
            String type,
            String status,
            int refundAmount,
            int shopBears,
            UUID vendorId,
            String vendorName,
            String customerName,
            @Schema(description = "ACCEPTED hoặc DISPUTED; vắng mặt khi quán không trả lời.") String shopResponse,
            Instant openedAt) {
    }

    @Schema(name = "AdminOrderCasePage")
    public record Page(List<Summary> items, int page, int size, long total) {
    }

    @Schema(name = "OrderCaseHistory", description = "Các khiếu nại khác của cùng người (không tính khiếu nại đang xem).")
    public record History(
            long total,
            long upheld,
            long dismissed,
            @Schema(description = "Số khiếu nại bị bác bỏ trong 90 ngày gần nhất; chạm ngưỡng `abuse.incident_dismissed_threshold` (3) thì cảnh báo.") long dismissedLast90Days) {
    }

    @Schema(name = "OrderCaseLogEntry")
    public record LogEntry(
            @Schema(description = "FILED, SHOP_ACCEPTED, SHOP_DISPUTED, NO_RESPONSE, UPHELD, DISMISSED hoặc REOPENED.") String action,
            @Schema(description = "CUSTOMER, SHOP, ADMIN hoặc SYSTEM.") String by,
            String detail,
            Instant at) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "AdminOrderCase", description = "Một khiếu nại kèm bằng chứng để quyết định.")
    public record Detail(
            CaseViews.Case orderCase,
            UUID customerId,
            UUID vendorId,
            String vendorName,
            History customerHistory,
            History shopHistory,
            @Schema(description = "Quán không trả lời kịp (khác với quán phản đối).") boolean noResponse,
            @Schema(description = "Đã được mở lại một lần; mỗi khiếu nại chỉ mở lại được một lần.") boolean reopened,
            List<LogEntry> log) {
    }
}
