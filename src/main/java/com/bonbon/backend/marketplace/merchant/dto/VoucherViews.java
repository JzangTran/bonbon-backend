package com.bonbon.backend.marketplace.merchant.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** A shop's vouchers as the seller manages them (manage-vouchers.md). */
public final class VoucherViews {

    private VoucherViews() {
    }

    @Schema(name = "ShopVoucher")
    public record Item(UUID id, String code, String discountType, int discountValue, Integer maxDiscount, Integer minOrderValue,
            Instant validFrom, Instant validTo, Integer usageLimit, int usedCount, Integer maxPerCustomer, String status) {
    }

    @Schema(name = "VoucherCreateRequest")
    public record Create(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,20}", message = "Mã gồm 3 đến 20 chữ, số, gạch ngang hoặc gạch dưới.") String code,
            @NotNull @Pattern(regexp = "PERCENT|FIXED", message = "Loại giảm phải là PERCENT hoặc FIXED.") String discountType,
            @NotNull @Positive @Max(10_000_000) Integer discountValue,
            @Positive @Max(10_000_000) Integer maxDiscount,
            @PositiveOrZero @Max(10_000_000) Integer minOrderValue,
            @NotNull Instant validFrom,
            @NotNull Instant validTo,
            @Positive @Max(10_000_000) Integer usageLimit,
            @Positive @Max(1_000) Integer maxPerCustomer) {
    }

    /** Only the fields present change; the code stays, it may already be printed. The `clear...` flags remove an optional value. */
    @Schema(name = "VoucherUpdateRequest")
    public record Update(
            @Pattern(regexp = "PERCENT|FIXED", message = "Loại giảm phải là PERCENT hoặc FIXED.") String discountType,
            @Positive @Max(10_000_000) Integer discountValue,
            @Positive @Max(10_000_000) Integer maxDiscount,
            @PositiveOrZero @Max(10_000_000) Integer minOrderValue,
            Instant validFrom,
            Instant validTo,
            @Min(1) @Max(10_000_000) Integer usageLimit,
            @Positive @Max(1_000) Integer maxPerCustomer,
            Boolean clearMaxDiscount,
            Boolean clearMinOrderValue,
            Boolean clearUsageLimit,
            Boolean clearMaxPerCustomer) {
    }

    @Schema(name = "VoucherStatusRequest")
    public record Status(@NotNull @Pattern(regexp = "ACTIVE|DISABLED", message = "Trạng thái phải là ACTIVE hoặc DISABLED.") String status) {
    }
}
