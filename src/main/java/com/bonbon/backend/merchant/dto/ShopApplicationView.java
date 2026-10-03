package com.bonbon.backend.merchant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The seller's own view of the shop application. {@code status} is NONE before the first saved step.
 * {@code openNow} is the effective state: approved, accepting orders and inside an opening window.
 * Secrets come back masked (last 4 digits); own document photos come as short-lived signed URLs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShopApplicationView(
        String status,
        String rejectionReason,
        Instant submittedAt,
        Integer firstIncompleteStep,
        List<StepState> steps,
        BigDecimal maxDeliveryRadiusKm,
        Boolean acceptingOrders,
        Boolean openNow,
        ShopInfo shop,
        Shipping shipping,
        Tax tax,
        Identity identity) {

    /** {@code missing} names the fields still needed for the step to be complete. */
    public record StepState(int step, boolean complete, List<String> missing) {
    }

    public record ShopInfo(String name, String phone, String email, Address address) {
    }

    public record Address(String placeId, String formattedAddress, String ward, String province, String detail,
            Double lat, Double lng) {
    }

    public record Shipping(List<Hours> openingHours, BigDecimal deliveryRadiusKm, Integer deliveryFee,
            Integer freeDeliveryThreshold, Integer minOrderValue) {
    }

    public record Hours(int weekday, LocalTime opensAt, LocalTime closesAt) {
    }

    public record Tax(String businessType, String businessName, String businessAddress, String taxCode,
            List<String> invoiceEmails, String businessLicenseUrl, Payout payout) {
    }

    /** {@code holderMatchesIdentity} is null until both names exist; a mismatch is a warning, not a block. */
    public record Payout(String bankName, String accountLast4, String accountHolderName, Boolean holderMatchesIdentity) {
    }

    public record Identity(String docType, String docNumberLast4, String fullName, String frontPhotoUrl,
            String selfiePhotoUrl, boolean accuracyConfirmed, boolean sellerTermsAccepted, boolean identityConsentGiven) {
    }
}
