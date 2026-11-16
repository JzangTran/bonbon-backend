package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The help-center chatbot (flows/support/chat-with-chatbot.md). */
public final class HelpChatDtos {

    private HelpChatDtos() {
    }

    @Schema(name = "HelpChatQuestion")
    public record Ask(
            @NotBlank @Size(min = 2, max = 300) String question,
            @Schema(description = "`CUSTOMER` hoặc `SELLER` khi chưa đăng nhập; đã đăng nhập thì lấy theo vai trò đang dùng.")
            @Pattern(regexp = "CUSTOMER|SELLER", message = "Đối tượng phải là CUSTOMER hoặc SELLER.") String audience) {
    }

    @Schema(name = "HelpChatSuggestion", description = "Bài trợ giúp khác cũng liên quan.")
    public record Suggestion(UUID articleId, String title) {
    }

    @Schema(name = "HelpChatAnswer", description = "Câu trả lời lấy nguyên văn từ một bài trợ giúp do quản trị viên viết; không có mô hình sinh văn bản.")
    public record Answer(
            @Schema(description = "Có bài trợ giúp phù hợp.") boolean matched,
            UUID articleId,
            String title,
            @Schema(description = "Nội dung bài trợ giúp, hoặc lời gợi ý xem trung tâm trợ giúp khi không tìm thấy.") String answer,
            List<Suggestion> suggestions) {
    }

    @Schema(name = "HelpChatUnanswered", description = "Một câu hỏi chưa có bài trợ giúp trả lời, gộp các lần hỏi giống nhau.")
    public record Unanswered(String question, String audience, long times, Instant lastAskedAt) {
    }
}
