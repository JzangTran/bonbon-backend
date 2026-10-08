package com.bonbon.backend.settlement.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public final class CommissionRequests {

    private CommissionRequests() {
    }

    @Schema(name = "CommissionRateRequest")
    public record Rate(
            @Schema(description = "Tỷ lệ hoa hồng đã gồm VAT, từ 0 đến 30, tối đa hai chữ số thập phân.", example = "10.5")
            @NotNull @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal ratePercent) {
    }
}
