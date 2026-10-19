package com.bonbon.backend.merchant.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopClosureChanged;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.merchant.entity.OpeningWindow;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An approved shop keeping its details current (edit-store-info.md) and switching order intake
 * (pause-orders.md). Fields the administrator once checked (the address) send the shop back to review;
 * the rest apply immediately.
 */
@Service
public class ShopProfileService {

    private final VendorRepository vendors;
    private final ShopAddressRules addressRules;
    private final DeliveryRadiusCap radiusCap;
    private final ShopApplicationService views;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    ShopProfileService(VendorRepository vendors, ShopAddressRules addressRules, DeliveryRadiusCap radiusCap, ShopApplicationService views, JdbcClient jdbc,
            ApplicationEventPublisher events) {
        this.vendors = vendors;
        this.addressRules = addressRules;
        this.radiusCap = radiusCap;
        this.views = views;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Transactional
    public ShopApplicationView update(CurrentPrincipal owner, ShopProfileRequests.Update req) {
        Vendor vendor = approvedShop(owner.id());
        if (req.name() != null) {
            vendor.setName(req.name().strip());
        }
        if (req.phone() != null) {
            vendor.setPhone(req.phone());
        }
        if (req.email() != null) {
            vendor.setEmail(req.email().strip());
        }
        boolean movesPlace = req.provinceCode() != null || req.wardCode() != null || req.lat() != null || req.lng() != null;
        boolean moved = false;
        if (movesPlace) {
            // The form sends the whole address; a field left out keeps its stored value.
            moved = addressRules.apply(vendor,
                    req.provinceCode() != null ? req.provinceCode() : vendor.getProvinceCode(),
                    req.wardCode() != null ? req.wardCode() : vendor.getWardCode(),
                    req.addressDetail() != null ? req.addressDetail() : vendor.getAddressDetail(),
                    req.lat() != null ? req.lat() : vendor.getLat(),
                    req.lng() != null ? req.lng() : vendor.getLng());
            if (moved) {
                vendor.returnToReview(Instant.now());
            }
        } else if (req.addressDetail() != null) {
            vendor.setAddressDetail(req.addressDetail().isBlank() ? null : req.addressDetail().strip());
        }
        if (req.deliveryRadiusKm() != null || req.deliveryFee() != null || req.freeDeliveryThreshold() != null
                || req.minOrderValue() != null || Boolean.TRUE.equals(req.clearFreeDeliveryThreshold())
                || Boolean.TRUE.equals(req.clearMinOrderValue())) {
            // A shop compliant at signup cannot edit its way past a cap lowered since.
            radiusCap.require(vendor, req.deliveryRadiusKm());
            vendor.setShipping(
                    req.deliveryRadiusKm() != null ? req.deliveryRadiusKm() : vendor.getDeliveryRadiusKm(),
                    req.deliveryFee() != null ? req.deliveryFee() : vendor.getDeliveryFee(),
                    Boolean.TRUE.equals(req.clearFreeDeliveryThreshold()) ? null
                            : req.freeDeliveryThreshold() != null ? req.freeDeliveryThreshold() : vendor.getFreeDeliveryThreshold(),
                    Boolean.TRUE.equals(req.clearMinOrderValue()) ? null
                            : req.minOrderValue() != null ? req.minOrderValue() : vendor.getMinOrderValue());
        }
        if (moved) {
            // The new place may be in an area with a lower limit than the radius the shop has.
            radiusCap.require(vendor, vendor.getDeliveryRadiusKm());
        }
        return views.toView(vendors.save(vendor));
    }

    @Transactional
    public ShopApplicationView replaceOpeningHours(CurrentPrincipal owner, ShopProfileRequests.OpeningHours req) {
        Vendor vendor = approvedShop(owner.id());
        List<OpeningWindow> windows = OpeningHoursRules.toWindows(req.openingHours());
        OpeningHoursRules.validate(windows);
        vendor.replaceOpeningHours(windows);
        return views.toView(vendors.save(vendor));
    }

    /** Overrides the schedule at once; orders already accepted are unaffected. */
    @Transactional
    public ShopApplicationView setAcceptingOrders(CurrentPrincipal owner, boolean accepting) {
        Vendor vendor = approvedShop(owner.id());
        if (accepting && vendor.getCommissionStage().pausesOrders()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAUSED_FOR_COMMISSION",
                    "Quán đang bị tạm ngưng nhận đơn vì chưa trả hoa hồng quá hạn. Trả khoản nợ để nhận đơn trở lại.");
        }
        vendor.setAcceptingOrders(accepting, owner.actorType(), owner.id(), Instant.now());
        return views.toView(vendors.save(vendor));
    }

    /**
     * The owner closes the shop for good (close-shop.md): from now on it is gone from browsing and takes no new orders, but the orders
     * it has go on. It becomes CLOSED when nothing is left to settle (merchantapproval). Needs a sign-in within the last 10 minutes.
     */
    @Transactional
    public ShopApplicationView requestClose(CurrentPrincipal owner, Instant authTime, ShopProfileRequests.Close request, Instant now) {
        if (authTime == null || authTime.plus(Duration.ofMinutes(10)).isBefore(now)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Hãy đăng nhập lại trước khi đóng cửa hàng (trong vòng 10 phút).");
        }
        if ("OTHER".equals(request.reason()) && (request.reasonText() == null || request.reasonText().isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_TEXT_REQUIRED", "Lý do khác cần ghi rõ.");
        }
        Vendor vendor = vendors.findByOwnerUserId(owner.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt.").withProperty("status", "NONE"));
        int changed = vendors.changeStatus(vendor.getId(), VendorStatus.APPROVED, VendorStatus.CLOSING);
        if (changed == 0) {
            changed = vendors.changeStatus(vendor.getId(), VendorStatus.SUSPENDED, VendorStatus.CLOSING);
        }
        if (changed == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_CANNOT_CLOSE", "Chỉ cửa hàng đang hoạt động hoặc đang bị đình chỉ mới đóng được.")
                    .withProperty("status", vendor.getStatus().name());
        }
        jdbc.sql("update vendors set closing_reason = :r, closing_reason_text = :t, closing_requested_at = :at where id = :id")
                .param("r", request.reason()).param("t", request.reasonText() == null || request.reasonText().isBlank() ? null : request.reasonText().strip())
                .param("at", java.sql.Timestamp.from(now)).param("id", vendor.getId()).update();
        events.publishEvent(new ShopClosureChanged(vendor.getId(), vendor.getOwnerUserId(), vendor.getName(), "CLOSING", request.reason()));
        return views.toView(vendors.findById(vendor.getId()).orElseThrow());
    }

    private Vendor approvedShop(UUID ownerId) {
        Vendor vendor = vendors.findByOwnerUserId(ownerId).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt.").withProperty("status", "NONE"));
        if (vendor.getStatus() != VendorStatus.APPROVED) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                    "Chỉ cửa hàng đã được duyệt mới sửa được thông tin ở đây.")
                    .withProperty("status", vendor.getStatus().name());
        }
        return vendor;
    }
}
