package com.bonbon.backend.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ContactRequests {

    private ContactRequests() {
    }

    @Schema(name = "SubmitContactRequest")
    public record Submit(
            @Schema(description = "Họ tên, tối đa 100 ký tự.") @NotBlank @Size(max = 100) String fullName,
            @Schema(description = "Email nhận câu trả lời.") @NotBlank @Email @Size(max = 255) String email,
            @Schema(description = "Người gửi là ai: `CUSTOMER` (đặt món), `SELLER` (bán hàng) hoặc `OTHER`.") @NotBlank @Pattern(regexp = "CUSTOMER|SELLER|OTHER") String role,
            @Schema(description = "Chủ đề: `ORDER`, `ACCOUNT`, `SHOP`, `PAYMENT` hoặc `OTHER`.") @NotBlank @Pattern(regexp = "ORDER|ACCOUNT|SHOP|PAYMENT|OTHER") String topic,
            @Schema(description = "Nội dung, tối đa 2.000 ký tự.") @NotBlank @Size(max = 2000) String message,
            @Schema(description = "Mã reCAPTCHA của biểu mẫu.") @Size(max = 4096) String captchaToken) {
    }

    @Schema(name = "AnswerContactRequest")
    public record Answer(@Schema(description = "Câu trả lời gửi tới email của người hỏi, tối đa 2.000 ký tự.") @NotBlank @Size(max = 2000) String body) {
    }
}
