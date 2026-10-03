package com.bonbon.backend.merchant;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shop applications as the review side sees them (merchant-approval flows). The identity section is a
 * separate call so the caller can gate it with its own permission and audit every access.
 */
public interface ShopApplications {

    /** Oldest submission first; {@code query} matches the shop name, ward or province (case-insensitive). */
    List<Summary> list(Set<VendorStatus> statuses, String query);

    Optional<Detail> detail(UUID vendorId);

    /** Decrypted document number and short-lived photo URLs. Callers must check permission and log access. */
    Optional<IdentityDocuments> identityDocuments(UUID vendorId, Duration urlTtl);

    /**
     * PENDING → APPROVED.
     *
     * @throws com.bonbon.backend.common.exception.BusinessException 409 SHOP_NOT_PENDING
     */
    Decided approve(UUID vendorId);

    /** PENDING → REJECTED with the reason the seller will see. */
    Decided reject(UUID vendorId, String reason);

    record Summary(UUID vendorId, UUID ownerUserId, String name, String ward, String province, String businessType,
            VendorStatus status, Instant submittedAt, Boolean payoutHolderMatchesIdentity) {
    }

    record Hours(int weekday, LocalTime opensAt, LocalTime closesAt) {
    }

    /** Everything an administrator reviews except the identity documents; the bank account comes masked. */
    record Detail(UUID vendorId, UUID ownerUserId, VendorStatus status, String rejectionReason, Instant submittedAt,
            Instant decidedAt, String name, String phone, String email, String formattedAddress, String addressDetail,
            String ward, String province, Double lat, Double lng, List<Hours> openingHours, BigDecimal deliveryRadiusKm,
            Integer deliveryFee, Integer freeDeliveryThreshold, Integer minOrderValue, String businessType,
            String businessName, String businessAddress, String taxCode, List<String> invoiceEmails,
            String businessLicenseUrl, String bankName, String accountLast4, String accountHolderName,
            Boolean payoutHolderMatchesIdentity, String identityDocType, String identityFullName) {
    }

    record IdentityDocuments(String docType, String docNumber, String fullName, String frontPhotoUrl, String selfiePhotoUrl) {
    }

    record Decided(UUID vendorId, UUID ownerUserId, String shopName, VendorStatus status) {
    }
}
