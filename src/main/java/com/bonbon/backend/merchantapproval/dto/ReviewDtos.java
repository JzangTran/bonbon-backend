package com.bonbon.backend.merchantapproval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.ShopApplications;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** One queue row; {@code resubmitted} marks a shop that was rejected before. */
    public record QueueRow(ShopApplications.Summary shop, boolean resubmitted) {
    }

    public record Decision(String decision, String reason, UUID decidedBy, Instant decidedAt) {
    }

    /** The application plus its earlier decisions, newest first. */
    public record Application(ShopApplications.Detail shop, List<Decision> history) {
    }

    public record RejectRequest(@NotBlank @Size(min = 5, max = 1000) String reason) {
    }

    public record RadiusCap(@NotNull @DecimalMin("0.5") @DecimalMax("50") @Digits(integer = 2, fraction = 1)
            BigDecimal maxRadiusKm) {
    }
}
