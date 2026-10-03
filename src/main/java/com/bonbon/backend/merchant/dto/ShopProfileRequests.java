package com.bonbon.backend.merchant.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Requests of an approved shop editing itself (edit-store-info.md, pause-orders.md). */
public final class ShopProfileRequests {

    private ShopProfileRequests() {
    }

    /**
     * Only the fields present change. A new {@code placeId} sends the shop back to review; everything else
     * applies at once. {@code clearFreeDeliveryThreshold} / {@code clearMinOrderValue} remove those options.
     */
    public record Update(
            @Size(min = 1, max = 100) String name,
            @Pattern(regexp = ShopApplicationRequests.VN_PHONE, message = "Số điện thoại không hợp lệ") String phone,
            @Email @Size(max = 255) String email,
            @Size(min = 1, max = 1024) String placeId,
            @Size(max = 200) String addressDetail,
            @DecimalMin("0.1") @DecimalMax("99.9") @Digits(integer = 2, fraction = 1) BigDecimal deliveryRadiusKm,
            @PositiveOrZero @Max(1_000_000) Integer deliveryFee,
            @PositiveOrZero @Max(100_000_000) Integer freeDeliveryThreshold,
            @PositiveOrZero @Max(100_000_000) Integer minOrderValue,
            Boolean clearFreeDeliveryThreshold,
            Boolean clearMinOrderValue) {
    }

    /** The whole weekly schedule; an empty list means closed every day. */
    public record OpeningHours(@NotNull @Size(max = 28) List<ShopApplicationRequests.@Valid @NotNull Window> openingHours) {
    }

    public record AcceptingOrders(@NotNull Boolean accepting) {
    }
}
