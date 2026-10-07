package com.bonbon.backend.payment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public final class RefundViews {

    private RefundViews() {
    }

    /** What the customer sees of their own refund: the account is masked to its last four digits. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "RefundStatus")
    public record Mine(String status, String mode, int amount, boolean needsDestination, String failureReason, String destinationLast4) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "RefundDestination", description = "Tài khoản khách nhập để nhận hoàn tiền; số đầy đủ chỉ người có quyền `refund:process` thấy.")
    public record Destination(String bankName, String accountNumber, String accountName) {
    }

    /** One row of the refund queue. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "RefundRow")
    public record Row(
            UUID id,
            UUID orderId,
            @Schema(description = "Mã đơn hiển thị.") long orderNumber,
            UUID customerId,
            int amount,
            @Schema(description = "ORDER_CLOSED (đơn đã trả tiền bị huỷ/từ chối), LATE_PAYMENT (tiền đến sau khi đơn đã đóng), CASE_UPHELD.") String reason,
            @Schema(description = "REQUESTED (chờ quản trị chuyển khoản), NEEDS_DESTINATION (chờ khách nhập tài khoản), PROCESSING, COMPLETED, FAILED.") String status,
            @Schema(description = "GATEWAY (hoàn qua MoMo) hoặc MANUAL (chuyển khoản).") String mode,
            @Schema(description = "Mã kết quả MoMo cuối cùng, vì sao hoàn qua MoMo không được.") Integer gatewayResultCode,
            Destination destination,
            @Schema(description = "Lý do lần chuyển khoản trước thất bại.") String failureReason,
            String bankReference,
            Instant transferredAt,
            Instant createdAt,
            Instant completedAt) {
    }

    @Schema(name = "RefundPage")
    public record Page(List<Row> items, int page, int size, long total) {
    }
}
