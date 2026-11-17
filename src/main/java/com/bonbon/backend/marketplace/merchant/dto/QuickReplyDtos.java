package com.bonbon.backend.marketplace.merchant.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Quick replies: short texts a shop sends in chat by typing "/" and the shortcut. */
public final class QuickReplyDtos {

    private QuickReplyDtos() {
    }

    @Schema(name = "QuickReplyRequest")
    public record Request(
            @Schema(description = "1–20 ký tự a-z, 0-9, gạch ngang; gõ `/` + mã trong ô chat để chèn.", example = "giao")
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,20}", message = "Mã gõ tắt 1–20 ký tự: chữ thường không dấu, số, gạch ngang.") String shortcut,
            @Schema(description = "Nội dung, 1–1000 ký tự.") @NotBlank @Size(max = 1000) String text) {
    }

    @Schema(name = "QuickReply")
    public record View(UUID id, String shortcut, String text) {
    }
}
