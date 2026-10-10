package com.bonbon.backend.messaging.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public final class ChatViews {

    private ChatViews() {
    }

    @Schema(name = "ConversationLastMessage", description = "Tin nhắn mới nhất của cuộc trò chuyện, để hiện xem trước.")
    public record LastMessage(
            @Schema(description = "Chữ của tin nhắn; rỗng nếu chỉ có ảnh.") String text,
            boolean hasImage,
            @Schema(description = "`CUSTOMER` hoặc `SHOP`.") String sender,
            Instant sentAt) {
    }

    @Schema(name = "ConversationSummary")
    public record Conversation(
            UUID id,
            UUID vendorId,
            String shopName,
            @Schema(description = "Tên tài khoản của khách.") String customerName,
            LastMessage lastMessage,
            @Schema(description = "Có tin của bên kia mới hơn lần đọc gần nhất của phía người gọi (khách, hoặc cả cửa hàng).") boolean unread) {
    }

    @Schema(name = "AdminConversationView", description = "Một cuộc trò chuyện khách – cửa hàng, cho quản trị viên đọc.")
    public record AdminConversation(UUID id, UUID vendorId, String shopName, UUID customerId, String customerName, Instant createdAt, Instant lastMessageAt) {
    }

    @Schema(name = "ConversationPage")
    public record Page(
            List<Conversation> items,
            int page,
            int size,
            long total,
            @Schema(description = "Số cuộc trò chuyện còn tin chưa đọc, để hiện số trên biểu tượng.") long unreadConversations) {
    }

    @Schema(name = "ChatReplyPreview", description = "Tin nhắn được trả lời, rút gọn.")
    public record Reply(UUID id, String text, boolean hasImage, @Schema(description = "`CUSTOMER` hoặc `SHOP`.") String sender) {
    }

    @Schema(name = "ChatMessage")
    public record Message(
            UUID id,
            UUID conversationId,
            @Schema(description = "`CUSTOMER` hoặc `SHOP`. Khách chỉ thấy \"cửa hàng\", không bao giờ thấy tên một nhân viên.") String sender,
            @Schema(description = "Tin do phía của người gọi gửi.") boolean mine,
            String text,
            @Schema(description = "Liên kết ngắn hạn tới ảnh (15 phút); rỗng nếu không có ảnh.") String imageUrl,
            Reply replyTo,
            Instant createdAt) {
    }

    @Schema(name = "ChatMessagePage", description = "Tin nhắn mới nhất trước.")
    public record Messages(
            List<Message> items,
            @Schema(description = "Còn tin cũ hơn nữa.") boolean hasMore,
            @Schema(description = "Truyền vào `before` để lấy trang cũ hơn; rỗng khi hết.") Instant nextBefore) {
    }

    @Schema(name = "ChatImageUpload")
    public record Upload(@Schema(description = "Gửi trong `imageKey` của tin nhắn.") String imageKey, String imageUrl) {
    }
}
