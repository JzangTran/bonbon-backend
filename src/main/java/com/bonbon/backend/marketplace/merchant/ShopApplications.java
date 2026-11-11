package com.bonbon.backend.marketplace.merchant;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Shop applications as the review side sees them (merchant-approval flows). The identity section is a
 * separate call so the caller can gate it with its own permission and audit every access.
 */
public interface ShopApplications {

    /** Oldest submission first; {@code query} matches the shop name, ward or province (case-insensitive). */
    List<Summary> list(Set<VendorStatus> statuses, String query);

    Optional<Detail> detail(UUID vendorId);

    /**
     * Other shops that look like this one for the reviewer: the same tax code, or an identity document with the same last four
     * digits and the same holder name (the document number itself is encrypted, so it cannot be compared exactly). A warning for a
     * person to look at, never a rejection.
     */
    List<Duplicate> duplicatesOf(UUID vendorId);

    /** Decrypted document number and short-lived photo URLs. Callers must check permission and log access. */
    Optional<IdentityDocuments> identityDocuments(UUID vendorId, Duration urlTtl);

    /**
     * PENDING → APPROVED.
     *
     * @throws com.bonbon.backend.common.exception.BusinessException 409 SHOP_NOT_PENDING
     */
    Decided approve(UUID vendorId);

    /**
     * APPROVED → SUSPENDED: the shop disappears from customers and takes no new orders; orders in progress can still be finished.
     *
     * @throws com.bonbon.backend.common.exception.BusinessException 409 SHOP_NOT_APPROVED
     */
    Decided suspend(UUID vendorId);

    /**
     * SUSPENDED → APPROVED.
     *
     * @throws com.bonbon.backend.common.exception.BusinessException 409 SHOP_NOT_SUSPENDED
     */
    Decided reinstate(UUID vendorId);

    /** PENDING → REJECTED with the reason the seller will see. */
    Decided reject(UUID vendorId, String reason);

    /** CLOSING -> CLOSED: nothing is left to settle. @throws com.bonbon.backend.common.exception.BusinessException 409 SHOP_NOT_CLOSING */
    Decided finishClosing(UUID vendorId);

    /** The shops waiting to be closed, oldest request first. */
    List<Closing> closing();

    @Schema(name = "ClosingShop")
    record Closing(UUID vendorId, UUID ownerUserId, String name, String reason, String reasonText, Instant requestedAt) {
    }

    @Schema(name = "ShopApplicationSummary")

    record Summary(UUID vendorId, UUID ownerUserId, String name, String ward, String province, String businessType,
            VendorStatus status, Instant submittedAt, Boolean payoutHolderMatchesIdentity) {
    }

    @Schema(name = "ShopReviewOpeningWindow")

    record Hours(int weekday, LocalTime opensAt, LocalTime closesAt) {
    }

    /** Everything an administrator reviews except the identity documents; the bank account comes masked. */
    @Schema(name = "ShopApplicationDetail")
    record Detail(UUID vendorId, UUID ownerUserId, VendorStatus status, String rejectionReason, Instant submittedAt,
            Instant decidedAt, String name, String phone, String email, String formattedAddress, String addressDetail,
            String ward, String province, Double lat, Double lng, List<Hours> openingHours, BigDecimal deliveryRadiusKm,
            Integer deliveryFee, Integer freeDeliveryThreshold, Integer minOrderValue, String businessType,
            String businessName, String businessAddress, String taxCode, List<String> invoiceEmails,
            String businessLicenseUrl, String bankName, String accountLast4, String accountHolderName,
            Boolean payoutHolderMatchesIdentity, String identityDocType, String identityFullName) {
    }

    @Schema(name = "ShopDuplicateWarning")
    record Duplicate(UUID vendorId, String name, VendorStatus status, @Schema(description = "TAX_CODE hoặc POSSIBLE_SAME_PERSON (cùng 4 số cuối giấy tờ và cùng họ tên).") String reason) {
    }

    @Schema(name = "ShopIdentityDocuments")

    record IdentityDocuments(String docType, String docNumber, String fullName, String frontPhotoUrl, String selfiePhotoUrl) {
    }

    @Schema(name = "ShopDecision")

    record Decided(UUID vendorId, UUID ownerUserId, String shopName, VendorStatus status) {
    }
}
