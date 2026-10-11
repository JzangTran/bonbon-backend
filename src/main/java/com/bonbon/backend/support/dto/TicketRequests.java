package com.bonbon.backend.support.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TicketRequests {

    private TicketRequests() {
    }

    @Schema(name = "OpenSupportTicketRequest")
    public record Open(
            @Schema(description = "Tiêu đề ngắn, tối đa 150 ký tự.") @NotBlank @Size(max = 150) String subject,
            @Schema(description = "Nội dung, tối đa 2.000 ký tự.") @NotBlank @Size(max = 2000) String message,
            @Schema(description = "Đơn liên quan, phải là đơn của chính người gọi (khách) hoặc của cửa hàng của người gọi (người bán).") UUID orderId,
            @Schema(description = "Khoá ảnh đã tải lên qua `POST /api/support/attachments`; tối đa 3.") @Size(max = 3) List<@NotBlank @Size(max = 300) String> attachmentKeys) {
    }

    @Schema(name = "TicketMessageRequest")
    public record Message(
            @NotBlank @Size(max = 2000) String body,
            @Schema(description = "Khoá ảnh đã tải lên; tối đa 3.") @Size(max = 3) List<@NotBlank @Size(max = 300) String> attachmentKeys) {
    }

    @Schema(name = "AdminTicketReplyRequest")
    public record Reply(@Schema(description = "Câu trả lời, tối đa 2.000 ký tự.") @NotBlank @Size(max = 2000) String body) {
    }
}
