package com.bonbon.backend.merchant.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.crypto.FieldCipher;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceDetail;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalConsentService;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.dto.ShopApplicationRequests;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.entity.OpeningWindow;
import com.bonbon.backend.merchant.entity.PayoutAccount;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.entity.VendorIdentity;
import com.bonbon.backend.merchant.entity.VendorTaxInfo;
import com.bonbon.backend.merchant.repository.PayoutAccountRepository;
import com.bonbon.backend.merchant.repository.VendorIdentityRepository;
import com.bonbon.backend.merchant.repository.VendorRepository;
import com.bonbon.backend.merchant.repository.VendorTaxInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The shop-opening wizard (flows/merchant/open-shop.md): five steps saved as drafts, files in the private
 * bucket, then submission for review. The application is editable only while DRAFT or REJECTED.
 */
@Service
public class ShopApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ShopApplicationService.class);
    private static final String PRINCIPAL_TYPE = "USER";
    private static final Duration OWN_FILE_URL_TTL = Duration.ofMinutes(5);
    private static final long IDENTITY_MAX_BYTES = 8L * 1024 * 1024;
    private static final long LICENCE_MAX_BYTES = 10L * 1024 * 1024;

    /** The files a seller uploads, and where each one goes. */
    public enum FileKind { BUSINESS_LICENSE, IDENTITY_FRONT, IDENTITY_SELFIE }

    private final VendorRepository vendors;
    private final VendorTaxInfoRepository taxInfos;
    private final VendorIdentityRepository identities;
    private final PayoutAccountRepository payouts;
    private final Geocoder geocoder;
    private final ObjectStorage storage;
    private final FieldCipher cipher;
    private final DeliveryRadiusCap radiusCap;
    private final LegalConsentService legal;

    ShopApplicationService(VendorRepository vendors, VendorTaxInfoRepository taxInfos, VendorIdentityRepository identities,
            PayoutAccountRepository payouts, Geocoder geocoder, ObjectStorage storage, FieldCipher cipher,
            DeliveryRadiusCap radiusCap, LegalConsentService legal) {
        this.vendors = vendors;
        this.taxInfos = taxInfos;
        this.identities = identities;
        this.payouts = payouts;
        this.geocoder = geocoder;
        this.storage = storage;
        this.cipher = cipher;
        this.radiusCap = radiusCap;
        this.legal = legal;
    }

    @Transactional(readOnly = true)
    public ShopApplicationView view(UUID ownerId) {
        return vendors.findByOwnerUserId(ownerId).map(this::toView)
                .orElseGet(() -> new ShopApplicationView("NONE", null, null, 1, List.of(), radiusCap.maxKm(),
                        null, null, null, null, null, null));
    }

    @Transactional
    public ShopApplicationView saveShopInfo(CurrentPrincipal owner, ShopApplicationRequests.Step1 req) {
        Vendor vendor = editableVendor(owner.id());
        vendor.setName(blankToNull(req.name()));
        vendor.setPhone(blankToNull(req.phone()));
        vendor.setEmail(blankToNull(req.email()));
        vendor.setAddressDetail(blankToNull(req.addressDetail()));
        String placeId = blankToNull(req.placeId());
        if (placeId == null) {
            vendor.setAddress(null, null, null, null, null, null);
        } else if (!placeId.equals(vendor.getPlaceId())) {
            // Place Detail is the expensive call: only when a different suggestion was picked.
            PlaceDetail place = geocoder.placeDetail(placeId).orElseThrow(() -> new BusinessException(
                    HttpStatus.BAD_REQUEST, "ADDRESS_NOT_FOUND", "Không xác định được địa chỉ này. Hãy chọn lại một gợi ý."));
            vendor.setAddress(place.placeId(), place.formattedAddress(), place.ward(), place.province(), place.lat(), place.lng());
        }
        return toView(vendors.save(vendor));
    }

    @Transactional
    public ShopApplicationView saveShipping(CurrentPrincipal owner, ShopApplicationRequests.Step2 req) {
        Vendor vendor = editableVendor(owner.id());
        List<OpeningWindow> windows = OpeningHoursRules.toWindows(req.openingHours());
        OpeningHoursRules.validate(windows);
        radiusCap.require(req.deliveryRadiusKm());
        vendor.replaceOpeningHours(windows);
        vendor.setShipping(req.deliveryRadiusKm(), req.deliveryFee(), req.freeDeliveryThreshold(), req.minOrderValue());
        return toView(vendors.save(vendor));
    }

    @Transactional
    public ShopApplicationView saveTaxAndPayout(CurrentPrincipal owner, ShopApplicationRequests.Step3 req) {
        Vendor vendor = editableVendor(owner.id());
        VendorTaxInfo tax = taxInfos.findById(vendor.getId()).orElseGet(() -> new VendorTaxInfo(vendor.getId()));
        List<String> emails = req.invoiceEmails() == null ? List.of()
                : req.invoiceEmails().stream().map(String::strip).distinct().toList();
        tax.update(req.businessType(), blankToNull(req.businessName()), blankToNull(req.businessAddress()),
                blankToNull(req.taxCode()), emails);
        taxInfos.save(tax);

        boolean anyPayoutField = req.bankName() != null || req.accountNumber() != null || req.accountHolderName() != null;
        Optional<PayoutAccount> existing = payouts.findActive(vendor.getId());
        if (existing.isPresent() || anyPayoutField) {
            PayoutAccount payout = existing.orElseGet(() -> new PayoutAccount(vendor.getId(), owner.actorType(), owner.id()));
            payout.update(blankToNull(req.bankName()), blankToNull(req.accountHolderName()));
            if (req.accountNumber() != null) {
                payout.setAccountNumber(cipher.encrypt(req.accountNumber()), last4(req.accountNumber()));
            }
            payouts.save(payout);
        }
        return toView(vendor);
    }

    @Transactional
    public ShopApplicationView saveIdentity(CurrentPrincipal owner, ShopApplicationRequests.Step4 req, ClientContext client) {
        Vendor vendor = editableVendor(owner.id());
        VendorIdentity identity = identities.findById(vendor.getId()).orElseGet(() -> new VendorIdentity(vendor.getId()));
        identity.setDocument(req.docType(), blankToNull(req.fullName()));
        if (req.docNumber() != null) {
            if (req.docType() == VendorIdentity.DocType.CCCD && req.docNumber().length() != 12) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DOC_NUMBER_INVALID", "Số CCCD gồm 12 chữ số.");
            }
            identity.setDocNumber(cipher.encrypt(req.docNumber()), last4(req.docNumber()));
        }
        if (req.accuracyConfirmed() != null) {
            identity.setAccuracyConfirmedAt(req.accuracyConfirmed() ? Instant.now() : null);
        }
        if (req.sellerTermsDocumentId() != null) {
            legal.recordAcceptance(PRINCIPAL_TYPE, owner.id(), List.of(DocumentType.SELLER_TERMS),
                    Set.of(req.sellerTermsDocumentId()), null, client);
            identity.setTermsAcceptedAt(Instant.now());
        }
        if (req.privacyPolicyDocumentId() != null) {
            legal.recordIdentityVerificationConsent(PRINCIPAL_TYPE, owner.id(), req.privacyPolicyDocumentId(), client);
            identity.setIdentityConsentAt(Instant.now());
        }
        identities.save(identity);
        return toView(vendor);
    }

    @Transactional
    public ShopApplicationView uploadFile(CurrentPrincipal owner, FileKind kind, MultipartFile upload) {
        Vendor vendor = editableVendor(owner.id());
        ValidatedFile file = kind == FileKind.BUSINESS_LICENSE
                ? ValidatedFile.of(upload, ValidatedFile.IMAGES_AND_PDF, LICENCE_MAX_BYTES)
                : ValidatedFile.of(upload, ValidatedFile.IMAGES, IDENTITY_MAX_BYTES);
        String prefix = (kind == FileKind.BUSINESS_LICENSE ? "vendor-licence/" : "vendor-identity/") + vendor.getId();
        String key = storage.put(Visibility.PRIVATE, prefix, file);
        cleanUpOnRollback(key);
        String replaced;
        if (kind == FileKind.BUSINESS_LICENSE) {
            VendorTaxInfo tax = taxInfos.findById(vendor.getId()).orElseGet(() -> new VendorTaxInfo(vendor.getId()));
            replaced = tax.getLicenseFileKey();
            tax.setLicenseFileKey(key);
            taxInfos.save(tax);
        } else {
            VendorIdentity identity = identities.findById(vendor.getId()).orElseGet(() -> new VendorIdentity(vendor.getId()));
            replaced = kind == FileKind.IDENTITY_FRONT ? identity.getFrontFileKey() : identity.getSelfieFileKey();
            if (kind == FileKind.IDENTITY_FRONT) {
                identity.setFrontFileKey(key);
            } else {
                identity.setSelfieFileKey(key);
            }
            identities.save(identity);
        }
        deleteAfterCommit(replaced);
        return toView(vendor);
    }

    @Transactional
    public ShopApplicationView submit(CurrentPrincipal owner) {
        Vendor vendor = vendors.findByOwnerUserId(owner.id()).orElseThrow(() -> new BusinessException(
                HttpStatus.BAD_REQUEST, "SHOP_APPLICATION_INCOMPLETE", "Hãy điền hồ sơ cửa hàng trước khi gửi duyệt.")
                .withProperty("firstIncompleteStep", 1));
        requireEditable(vendor);
        List<ShopApplicationView.StepState> steps = steps(vendor);
        Optional<ShopApplicationView.StepState> incomplete = steps.stream().filter(s -> !s.complete()).findFirst();
        if (incomplete.isPresent()) {
            Map<String, List<String>> missing = new LinkedHashMap<>();
            steps.stream().filter(s -> !s.complete()).forEach(s -> missing.put(String.valueOf(s.step()), s.missing()));
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SHOP_APPLICATION_INCOMPLETE",
                    "Hồ sơ còn thiếu thông tin ở bước " + incomplete.get().step() + ".")
                    .withProperty("firstIncompleteStep", incomplete.get().step())
                    .withProperty("missing", missing);
        }
        // The cap may have been lowered since step 2 was saved.
        radiusCap.require(vendor.getDeliveryRadiusKm());
        vendor.submit(Instant.now());
        return toView(vendors.save(vendor));
    }

    // --- completeness and view

    private List<ShopApplicationView.StepState> steps(Vendor v) {
        VendorTaxInfo tax = taxInfos.findById(v.getId()).orElse(null);
        PayoutAccount payout = payouts.findActive(v.getId()).orElse(null);
        VendorIdentity id = identities.findById(v.getId()).orElse(null);

        List<String> s1 = new ArrayList<>();
        need(s1, v.getName(), "name");
        need(s1, v.getPhone(), "phone");
        need(s1, v.getEmail(), "email");
        need(s1, v.getLat(), "address");

        List<String> s2 = new ArrayList<>();
        if (v.getOpeningHours().isEmpty()) {
            s2.add("openingHours");
        }
        need(s2, v.getDeliveryRadiusKm(), "deliveryRadiusKm");
        need(s2, v.getDeliveryFee(), "deliveryFee");

        List<String> s3 = new ArrayList<>();
        need(s3, tax == null ? null : tax.getBusinessType(), "businessType");
        need(s3, tax == null ? null : tax.getBusinessAddress(), "businessAddress");
        need(s3, tax == null ? null : tax.getTaxCode(), "taxCode");
        if (tax == null || tax.getInvoiceEmails().isEmpty()) {
            s3.add("invoiceEmails");
        }
        if (tax != null && tax.getBusinessType() == VendorTaxInfo.BusinessType.HOUSEHOLD) {
            need(s3, tax.getBusinessName(), "businessName");
            need(s3, tax.getLicenseFileKey(), "businessLicense");
        }
        need(s3, payout == null ? null : payout.getBankName(), "bankName");
        need(s3, payout == null ? null : payout.getAccountNumberEncrypted(), "accountNumber");
        need(s3, payout == null ? null : payout.getAccountHolderName(), "accountHolderName");

        List<String> s4 = new ArrayList<>();
        need(s4, id == null ? null : id.getDocType(), "docType");
        need(s4, id == null ? null : id.getDocNumberEncrypted(), "docNumber");
        need(s4, id == null ? null : id.getFullName(), "fullName");
        need(s4, id == null ? null : id.getFrontFileKey(), "frontPhoto");
        need(s4, id == null ? null : id.getSelfieFileKey(), "selfiePhoto");
        need(s4, id == null ? null : id.getAccuracyConfirmedAt(), "accuracyConfirmed");
        need(s4, id == null ? null : id.getTermsAcceptedAt(), "sellerTerms");
        need(s4, id == null ? null : id.getIdentityConsentAt(), "identityConsent");

        return List.of(state(1, s1), state(2, s2), state(3, s3), state(4, s4));
    }

    ShopApplicationView toView(Vendor v) {
        List<ShopApplicationView.StepState> steps = steps(v);
        Integer firstIncomplete = steps.stream().filter(s -> !s.complete()).map(ShopApplicationView.StepState::step)
                .findFirst().orElse(null);
        VendorTaxInfo tax = taxInfos.findById(v.getId()).orElse(null);
        PayoutAccount payout = payouts.findActive(v.getId()).orElse(null);
        VendorIdentity id = identities.findById(v.getId()).orElse(null);

        var shop = new ShopApplicationView.ShopInfo(v.getName(), v.getPhone(), v.getEmail(),
                new ShopApplicationView.Address(v.getPlaceId(), v.getFormattedAddress(), v.getWard(), v.getProvince(),
                        v.getAddressDetail(), v.getLat(), v.getLng()));
        var shipping = new ShopApplicationView.Shipping(
                v.getOpeningHours().stream().map(w -> new ShopApplicationView.Hours(w.weekday(), w.opensAt(), w.closesAt())).toList(),
                v.getDeliveryRadiusKm(), v.getDeliveryFee(), v.getFreeDeliveryThreshold(), v.getMinOrderValue());
        var payoutView = payout == null ? null : new ShopApplicationView.Payout(payout.getBankName(), payout.getAccountLast4(),
                payout.getAccountHolderName(), PersonNames.sameName(payout.getAccountHolderName(), id == null ? null : id.getFullName()));
        var taxView = tax == null && payout == null ? null : new ShopApplicationView.Tax(
                tax == null || tax.getBusinessType() == null ? null : tax.getBusinessType().name(),
                tax == null ? null : tax.getBusinessName(), tax == null ? null : tax.getBusinessAddress(),
                tax == null ? null : tax.getTaxCode(), tax == null ? List.of() : tax.getInvoiceEmails(),
                tax == null ? null : signed(tax.getLicenseFileKey()), payoutView);
        var identityView = id == null ? null : new ShopApplicationView.Identity(
                id.getDocType() == null ? null : id.getDocType().name(), id.getDocNumberLast4(), id.getFullName(),
                signed(id.getFrontFileKey()), signed(id.getSelfieFileKey()), id.getAccuracyConfirmedAt() != null,
                id.getTermsAcceptedAt() != null, id.getIdentityConsentAt() != null);
        return new ShopApplicationView(v.getStatus().name(), v.getRejectionReason(), v.getSubmittedAt(), firstIncomplete,
                steps, radiusCap.maxKm(), v.isAcceptingOrders(), ShopHours.isOpen(v, ZonedDateTime.now()), shop, shipping,
                taxView, identityView);
    }

    // --- helpers

    private Vendor editableVendor(UUID ownerId) {
        Vendor vendor = vendors.findByOwnerUserId(ownerId).orElseGet(() -> vendors.save(new Vendor(ownerId)));
        requireEditable(vendor);
        return vendor;
    }

    private static void requireEditable(Vendor vendor) {
        if (!vendor.getStatus().editableByWizard()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_EDITABLE",
                    vendor.getStatus() == VendorStatus.PENDING
                            ? "Hồ sơ đang chờ duyệt nên chưa sửa được."
                            : "Cửa hàng đã được duyệt; hãy sửa thông tin trong phần quản lý cửa hàng.")
                    .withProperty("status", vendor.getStatus().name());
        }
    }

    private String signed(String key) {
        return key == null ? null : storage.signedUrl(key, OWN_FILE_URL_TTL);
    }

    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(Visibility.PRIVATE, key);
                } catch (RuntimeException e) {
                    log.warn("Could not delete replaced file {}", key, e);
                }
            }
        });
    }

    private void cleanUpOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(Visibility.PRIVATE, key);
                    } catch (RuntimeException e) {
                        log.warn("Could not delete orphaned upload {}", key, e);
                    }
                }
            }
        });
    }

    private static ShopApplicationView.StepState state(int step, List<String> missing) {
        return new ShopApplicationView.StepState(step, missing.isEmpty(), List.copyOf(missing));
    }

    private static void need(List<String> missing, Object value, String field) {
        if (value == null) {
            missing.add(field);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String last4(String digits) {
        return digits.substring(Math.max(0, digits.length() - 4));
    }
}
