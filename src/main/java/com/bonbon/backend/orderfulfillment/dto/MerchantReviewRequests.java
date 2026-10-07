package com.bonbon.backend.orderfulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class MerchantReviewRequests {

    private MerchantReviewRequests() {
    }

    @Schema(name = "ReviewReplyRequest")
    public record Reply(@Schema(description = "Nội dung phản hồi, tối đa 1000 ký tự.") @NotBlank @Size(max = 1000) String text) {
    }
}
