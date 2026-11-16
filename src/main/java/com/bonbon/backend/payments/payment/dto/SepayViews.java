package com.bonbon.backend.payments.payment.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Automatic transfer confirmation through SePay. */
public final class SepayViews {

    private SepayViews() {
    }

    @Schema(name = "SepayStatus")
    public record Status(boolean connected,
            @Schema(description = "Địa chỉ nhận webhook để dán vào SePay.") String webhookUrl,
            Instant connectedAt, Instant lastEventAt,
            @Schema(description = "Mười giao dịch gần nhất SePay báo về.") List<Event> recent) {
    }

    @Schema(name = "SepayEvent")
    public record Event(long sepayId, long amount, String content, Long orderNumber,
            @Schema(description = "`PAID` đã ghi nhận; `NOT_FOUND` không có đơn; `NOT_A_BANK_TRANSFER`; `ALREADY_PAID`; `AMOUNT_SHORT` thiếu tiền; `NO_CODE` nội dung không có mã đơn; `OUTGOING` tiền ra.")
            String outcome, Instant receivedAt) {
    }

    @Schema(name = "SepayConnection", description = "Khoá chỉ hiện một lần; dán vào mục API Key của webhook trong SePay.")
    public record Connection(String webhookUrl, String apiKey) {
    }

    /** What SePay posts for each transaction (docs.sepay.vn, webhook). Unknown fields are ignored. */
    public record Webhook(Long id, String gateway, String transactionDate, String accountNumber, String code, String content,
            String transferType, Long transferAmount, String referenceCode) {
    }

    @Schema(name = "SepayAck")
    public record Ack(boolean success) {
    }
}
