package com.bonbon.backend.marketplace.servicearea.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ServiceAreaRequests {

    private ServiceAreaRequests() {
    }

    @Schema(name = "ServiceAreaRequest", description = "Một khu vực phục vụ: hình tròn quanh một tâm. Quán nằm trong hình tròn thuộc khu vực; bán kính giao của quán không vượt `maxDeliveryRadiusKm`.")
    public record Save(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotNull @DecimalMin("6") @DecimalMax("24") Double centerLat,
            @NotNull @DecimalMin("102") @DecimalMax("118") Double centerLng,
            @NotNull @DecimalMin("0.1") @DecimalMax("50") @Digits(integer = 2, fraction = 1) BigDecimal radiusKm,
            @NotNull @DecimalMin("0.1") @DecimalMax("50") @Digits(integer = 2, fraction = 1) BigDecimal maxDeliveryRadiusKm,
            Boolean active) {
    }
}
