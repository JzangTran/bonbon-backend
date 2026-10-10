package com.bonbon.backend.messaging.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public final class ChatRequests {

    private ChatRequests() {
    }

    @Schema(name = "SendMessageRequest", description = "Cần ít nhất `text` hoặc `imageKey`.")
    public record Send(
            @Schema(description = "Nội dung chữ, tối đa 1.000 ký tự (emoji là chữ bình thường).") @Size(max = 1000) String text,
            @Schema(description = "Khoá ảnh đã tải lên qua `POST /api/conversations/images`; mỗi tin nhắn một ảnh.") @Size(max = 300) String imageKey,
            @Schema(description = "Id tin nhắn đang được trả lời; phải thuộc cùng cuộc trò chuyện.") UUID replyToMessageId) {
    }
}
