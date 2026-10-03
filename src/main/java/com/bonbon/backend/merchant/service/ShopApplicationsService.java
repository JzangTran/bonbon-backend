package com.bonbon.backend.merchant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.crypto.FieldCipher;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.PayoutAccount;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.entity.VendorIdentity;
import com.bonbon.backend.merchant.entity.VendorTaxInfo;
import com.bonbon.backend.merchant.repository.PayoutAccountRepository;
import com.bonbon.backend.merchant.repository.VendorIdentityRepository;
import com.bonbon.backend.merchant.repository.VendorRepository;
import com.bonbon.backend.merchant.repository.VendorTaxInfoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The merchant side of shop review: reading applications and applying the decision to the shop. */
@Service
class ShopApplicationsService implements ShopApplications {

    private static final Duration LICENCE_URL_TTL = Duration.ofMinutes(5);

    private final VendorRepository vendors;
    private final VendorTaxInfoRepository taxInfos;
    private final VendorIdentityRepository identities;
    private final PayoutAccountRepository payouts;
    private final ObjectStorage storage;
    private final FieldCipher cipher;

    ShopApplicationsService(VendorRepository vendors, VendorTaxInfoRepository taxInfos, VendorIdentityRepository identities,
            PayoutAccountRepository payouts, ObjectStorage storage, FieldCipher cipher) {
        this.vendors = vendors;
        this.taxInfos = taxInfos;
        this.identities = identities;
        this.payouts = payouts;
        this.storage = storage;
        this.cipher = cipher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Summary> list(Set<VendorStatus> statuses, String query) {
        String pattern = "%" + (query == null ? "" : query.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return vendors.search(statuses, pattern).stream().map(v -> {
            VendorTaxInfo tax = taxInfos.findById(v.getId()).orElse(null);
            return new Summary(v.getId(), v.getOwnerUserId(), v.getName(), v.getWard(), v.getProvince(),
                    tax == null || tax.getBusinessType() == null ? null : tax.getBusinessType().name(), v.getStatus(),
                    v.getSubmittedAt(), holderMatches(v.getId()));
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Detail> detail(UUID vendorId) {
        return vendors.findById(vendorId).map(v -> {
            VendorTaxInfo tax = taxInfos.findById(v.getId()).orElse(null);
            PayoutAccount payout = payouts.findActive(v.getId()).orElse(null);
            VendorIdentity id = identities.findById(v.getId()).orElse(null);
            return new Detail(v.getId(), v.getOwnerUserId(), v.getStatus(), v.getRejectionReason(), v.getSubmittedAt(),
                    v.getDecidedAt(), v.getName(), v.getPhone(), v.getEmail(), v.getFormattedAddress(), v.getAddressDetail(),
                    v.getWard(), v.getProvince(), v.getLat(), v.getLng(),
                    v.getOpeningHours().stream().map(w -> new Hours(w.weekday(), w.opensAt(), w.closesAt())).toList(),
                    v.getDeliveryRadiusKm(), v.getDeliveryFee(), v.getFreeDeliveryThreshold(), v.getMinOrderValue(),
                    tax == null || tax.getBusinessType() == null ? null : tax.getBusinessType().name(),
                    tax == null ? null : tax.getBusinessName(), tax == null ? null : tax.getBusinessAddress(),
                    tax == null ? null : tax.getTaxCode(), tax == null ? List.of() : tax.getInvoiceEmails(),
                    tax == null || tax.getLicenseFileKey() == null ? null : storage.signedUrl(tax.getLicenseFileKey(), LICENCE_URL_TTL),
                    payout == null ? null : payout.getBankName(), payout == null ? null : payout.getAccountLast4(),
                    payout == null ? null : payout.getAccountHolderName(),
                    PersonNames.sameName(payout == null ? null : payout.getAccountHolderName(), id == null ? null : id.getFullName()),
                    id == null || id.getDocType() == null ? null : id.getDocType().name(), id == null ? null : id.getFullName());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityDocuments> identityDocuments(UUID vendorId, Duration urlTtl) {
        return identities.findById(vendorId).map(id -> new IdentityDocuments(
                id.getDocType() == null ? null : id.getDocType().name(),
                id.getDocNumberEncrypted() == null ? null : cipher.decrypt(id.getDocNumberEncrypted()),
                id.getFullName(),
                id.getFrontFileKey() == null ? null : storage.signedUrl(id.getFrontFileKey(), urlTtl),
                id.getSelfieFileKey() == null ? null : storage.signedUrl(id.getSelfieFileKey(), urlTtl)));
    }

    @Override
    @Transactional
    public Decided approve(UUID vendorId) {
        Vendor vendor = pending(vendorId);
        vendor.approve(Instant.now());
        return decided(vendors.save(vendor));
    }

    @Override
    @Transactional
    public Decided reject(UUID vendorId, String reason) {
        Vendor vendor = pending(vendorId);
        vendor.reject(reason, Instant.now());
        return decided(vendors.save(vendor));
    }

    private Vendor pending(UUID vendorId) {
        Vendor vendor = vendors.findById(vendorId).orElseThrow(() -> BusinessException.notFound("SHOP_NOT_FOUND",
                "Không tìm thấy cửa hàng."));
        if (vendor.getStatus() != VendorStatus.PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_PENDING", "Hồ sơ này không ở trạng thái chờ duyệt.")
                    .withProperty("status", vendor.getStatus().name());
        }
        return vendor;
    }

    private Boolean holderMatches(UUID vendorId) {
        String holder = payouts.findActive(vendorId).map(PayoutAccount::getAccountHolderName).orElse(null);
        String owner = identities.findById(vendorId).map(VendorIdentity::getFullName).orElse(null);
        return PersonNames.sameName(holder, owner);
    }

    private static Decided decided(Vendor v) {
        return new Decided(v.getId(), v.getOwnerUserId(), v.getName(), v.getStatus());
    }
}
