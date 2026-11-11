package com.bonbon.backend.payments.payment.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Linked payment methods and the payment PIN (Cài đặt thanh toán). */
public final class PaymentSettingsViews {

    private PaymentSettingsViews() {
    }

    @Schema(name = "LinkedPaymentMethod", description = "`maskedAccount` hides all but the last four digits, as the provider shows it.")
    public record Method(UUID id, String provider, String name, String maskedAccount, boolean primary) {
    }

    @Schema(name = "PaymentMethods", description = "`linkable` lists the providers not linked yet; empty when every one is.")
    public record Methods(List<Method> methods, List<String> linkable) {
    }

    @Schema(name = "LinkPaymentMethodRequest", description = "MoMo: the phone number of the MoMo wallet.")
    public record Link(@NotBlank String provider, @NotBlank @Pattern(regexp = "^(0|\\+84)(3|5|7|8|9)\\d{8}$", message = "Số điện thoại di động Việt Nam không hợp lệ") String account) {
    }

    @Schema(name = "PaymentMethodPrimaryRequest")
    public record Primary(@NotNull Boolean primary) {
    }

    @Schema(name = "PaymentPinStatus")
    public record PinStatus(boolean set) {
    }

    @Schema(name = "PaymentPinRequest", description = "`currentPin` is required when a PIN is already set.")
    public record PinChange(@Pattern(regexp = "^\\d{6}$", message = "Mã PIN gồm 6 chữ số") String currentPin,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Mã PIN gồm 6 chữ số") String newPin) {
    }
}
