package com.bonbon.backend.merchant.dto;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.VendorIdentity.DocType;
import com.bonbon.backend.merchant.entity.VendorTaxInfo.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One request per wizard step. A PUT replaces that step's data, and any field may be missing (a draft);
 * what is present must be well-formed. Write-only secrets ({@code accountNumber}, {@code docNumber}) keep
 * their stored value when absent, since the API never returns them.
 */
public final class ShopApplicationRequests {

    /** Vietnamese phone: mobile or landline, 0 or +84 then 9–10 digits. */
    public static final String VN_PHONE = "^(0|\\+84)\\d{9,10}$";
    /** Tax code: 10 digits, 10 + 3-digit branch suffix, or the 12-digit personal number. */
    public static final String TAX_CODE = "^(\\d{10}(-\\d{3})?|\\d{12})$";

    private ShopApplicationRequests() {
    }

    @Schema(name = "ShopApplicationStep1Request")

    public record Step1(
            @Size(max = 100) String name,
            @Pattern(regexp = VN_PHONE, message = "Số điện thoại không hợp lệ") String phone,
            @Email @Size(max = 255) String email,
            @Size(max = 1024) String placeId,
            @Size(max = 200) String addressDetail) {
    }

    @Schema(name = "OpeningWindowInput")

    public record Window(
            @NotNull @Min(1) @Max(7) Integer weekday,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt) {
    }

    @Schema(name = "ShopApplicationStep2Request")

    public record Step2(
            @Size(max = 28) List<@Valid @NotNull Window> openingHours,
            @DecimalMin("0.1") @DecimalMax("99.9") @Digits(integer = 2, fraction = 1) BigDecimal deliveryRadiusKm,
            @PositiveOrZero @Max(1_000_000) Integer deliveryFee,
            @PositiveOrZero @Max(100_000_000) Integer freeDeliveryThreshold,
            @PositiveOrZero @Max(100_000_000) Integer minOrderValue) {
    }

    @Schema(name = "ShopApplicationStep3Request")

    public record Step3(
            BusinessType businessType,
            @Size(max = 200) String businessName,
            @Size(max = 300) String businessAddress,
            @Pattern(regexp = TAX_CODE, message = "Mã số thuế không hợp lệ") String taxCode,
            @Size(max = 5, message = "Tối đa 5 email nhận hoá đơn") List<@NotBlank @Email @Size(max = 255) String> invoiceEmails,
            @Size(max = 100) String bankName,
            @Pattern(regexp = "^\\d{6,19}$", message = "Số tài khoản gồm 6–19 chữ số") String accountNumber,
            @Size(max = 100) String accountHolderName) {
    }

    /**
     * {@code sellerTermsDocumentId} and {@code privacyPolicyDocumentId} are the two separate consents of
     * step 4; each is recorded when sent (the versions shown) and kept afterwards.
     */
    @Schema(name = "ShopApplicationStep4Request")
    public record Step4(
            DocType docType,
            @Pattern(regexp = "^(\\d{9}|\\d{12})$", message = "Số giấy tờ gồm 9 hoặc 12 chữ số") String docNumber,
            @Size(max = 100) String fullName,
            Boolean accuracyConfirmed,
            UUID sellerTermsDocumentId,
            UUID privacyPolicyDocumentId) {
    }
}
