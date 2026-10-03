package com.bonbon.backend.merchantapproval.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchantapproval.ShopReviewed;
import com.bonbon.backend.merchantapproval.dto.ReviewDtos;
import com.bonbon.backend.merchantapproval.entity.IdentityAccess;
import com.bonbon.backend.merchantapproval.entity.ReviewDecision;
import com.bonbon.backend.merchantapproval.repository.IdentityAccessRepository;
import com.bonbon.backend.merchantapproval.repository.ReviewDecisionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviewing shop applications (view-registration-requests.md, approve-seller.md, reject-seller.md) and the
 * delivery radius cap (set-delivery-radius-cap.md). The shop's own state changes through merchant's API.
 */
@Service
public class ShopReviewService {

    static final String RADIUS_CAP_KEY = "merchant.max_delivery_radius_km";
    private static final Duration IDENTITY_URL_TTL = Duration.ofMinutes(5);

    private final ShopApplications shops;
    private final ReviewDecisionRepository decisions;
    private final IdentityAccessRepository identityAccess;
    private final SystemSettingsService settings;
    private final ApplicationEventPublisher events;

    ShopReviewService(ShopApplications shops, ReviewDecisionRepository decisions, IdentityAccessRepository identityAccess,
            SystemSettingsService settings, ApplicationEventPublisher events) {
        this.shops = shops;
        this.decisions = decisions;
        this.identityAccess = identityAccess;
        this.settings = settings;
        this.events = events;
    }

    /** {@code status}: PENDING (default), REJECTED, APPROVED or ALL submitted applications. */
    @Transactional(readOnly = true)
    public List<ReviewDtos.QueueRow> queue(String status, String query) {
        Set<VendorStatus> statuses = switch (status == null ? "PENDING" : status) {
            case "PENDING" -> EnumSet.of(VendorStatus.PENDING);
            case "REJECTED" -> EnumSet.of(VendorStatus.REJECTED);
            case "APPROVED" -> EnumSet.of(VendorStatus.APPROVED);
            case "ALL" -> EnumSet.of(VendorStatus.PENDING, VendorStatus.REJECTED, VendorStatus.APPROVED, VendorStatus.SUSPENDED);
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "STATUS_FILTER_INVALID", "Bộ lọc trạng thái không hợp lệ.");
        };
        return shops.list(statuses, query).stream()
                .map(s -> new ReviewDtos.QueueRow(s,
                        decisions.existsByVendorIdAndDecision(s.vendorId(), ReviewDecision.Decision.REJECTED)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewDtos.Application application(UUID vendorId) {
        ShopApplications.Detail detail = shops.detail(vendorId)
                .filter(d -> d.status() != VendorStatus.DRAFT)
                .orElseThrow(ShopReviewService::notFound);
        List<ReviewDtos.Decision> history = decisions.findByVendorIdOrderByDecidedAtDesc(vendorId).stream()
                .map(d -> new ReviewDtos.Decision(d.getDecision().name(), d.getReason(), d.getDecidedById(), d.getDecidedAt()))
                .toList();
        return new ReviewDtos.Application(detail, history);
    }

    /** Every call is logged before the documents are returned. */
    @Transactional
    public ShopApplications.IdentityDocuments identity(CurrentPrincipal admin, UUID vendorId, String ip) {
        shops.detail(vendorId).filter(d -> d.status() != VendorStatus.DRAFT).orElseThrow(ShopReviewService::notFound);
        identityAccess.save(new IdentityAccess(vendorId, admin.id(), ip));
        return shops.identityDocuments(vendorId, IDENTITY_URL_TTL).orElseThrow(ShopReviewService::notFound);
    }

    @Transactional
    public ShopApplications.Decided approve(CurrentPrincipal admin, UUID vendorId) {
        ShopApplications.Decided decided = shops.approve(vendorId);
        decisions.save(new ReviewDecision(vendorId, ReviewDecision.Decision.APPROVED, null, admin.actorType(), admin.id()));
        events.publishEvent(new ShopReviewed(vendorId, decided.ownerUserId(), decided.shopName(), true, null));
        return decided;
    }

    @Transactional
    public ShopApplications.Decided reject(CurrentPrincipal admin, UUID vendorId, String reason) {
        String trimmed = reason.strip();
        ShopApplications.Decided decided = shops.reject(vendorId, trimmed);
        decisions.save(new ReviewDecision(vendorId, ReviewDecision.Decision.REJECTED, trimmed, admin.actorType(), admin.id()));
        events.publishEvent(new ShopReviewed(vendorId, decided.ownerUserId(), decided.shopName(), false, trimmed));
        return decided;
    }

    public ReviewDtos.RadiusCap radiusCap() {
        return new ReviewDtos.RadiusCap(new BigDecimal(settings.getString(RADIUS_CAP_KEY, "3")));
    }

    /** Applies to saves from now on; shops already approved keep their radius until they next edit it. */
    @Transactional
    public ReviewDtos.RadiusCap setRadiusCap(CurrentPrincipal admin, BigDecimal maxRadiusKm) {
        settings.set(RADIUS_CAP_KEY, maxRadiusKm.stripTrailingZeros().toPlainString(), admin.actorType(), admin.id());
        return radiusCap();
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("SHOP_APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ cửa hàng.");
    }
}
