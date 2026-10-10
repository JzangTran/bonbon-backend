package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class TicketViews {

    private TicketViews() {
    }

    @Schema(name = "SupportAttachment")
    public record Attachment(String key, @Schema(description = "Liên kết ngắn hạn (15 phút).") String url) {
    }

    @Schema(name = "SupportAttachmentUpload")
    public record Upload(String attachmentKey, String url) {
    }

    @Schema(name = "SupportTicketMessage")
    public record Message(UUID id,
            @Schema(description = "`USER` là người mở phiếu, `SUPPORT` là đội hỗ trợ (không bao giờ lộ tên quản trị viên).") String author,
            String body, List<Attachment> attachments, Instant createdAt) {
    }

    @Schema(name = "SupportTicketSummary")
    public record Summary(UUID id, String subject,
            @Schema(description = "OPEN (đang chờ hỗ trợ trả lời), ANSWERED (đã trả lời, chờ người dùng) hoặc CLOSED.") String status,
            Long orderNumber, Instant createdAt, Instant updatedAt) {
    }

    @Schema(name = "SupportTicketPage")
    public record Page(List<Summary> items, int page, int size, long total) {
    }

    @Schema(name = "SupportTicketDetail")
    public record Detail(UUID id, String subject, String status, UUID orderId, Long orderNumber, Instant createdAt, Instant updatedAt, Instant closedAt,
            @Schema(description = "USER, ADMIN hoặc SYSTEM; SYSTEM là tự đóng sau thời gian không ai trả lời.") String closedBy,
            List<Message> messages) {
    }

    @Schema(name = "AdminSupportTicketRow")
    public record AdminRow(UUID id, String subject, String status, UUID userId, String userName,
            @Schema(description = "CUSTOMER hoặc SHOP: phía người dùng mở phiếu.") String audience, Long orderNumber, Instant createdAt, Instant updatedAt) {
    }

    @Schema(name = "AdminSupportTicketPage")
    public record AdminPage(List<AdminRow> items, int page, int size, long total) {
    }

    @Schema(name = "AdminSupportTicketDetail", description = "Phiếu kèm toàn bộ tin nhắn. Không kèm thông tin liên hệ: dùng tra cứu khách theo `userId` và tra cứu đơn theo `orderId` (cần quyền riêng).")
    public record AdminDetail(UUID id, String subject, String status, UUID userId, String userName, String audience, UUID orderId, Long orderNumber,
            Instant createdAt, Instant updatedAt, Instant closedAt, String closedBy, List<AdminMessage> messages) {
    }

    @Schema(name = "AdminSupportTicketMessage")
    public record AdminMessage(UUID id, String author, UUID authorId, String body, List<Attachment> attachments, Instant createdAt) {
    }
}
