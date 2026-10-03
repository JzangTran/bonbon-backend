package com.bonbon.backend.merchant.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceDetail;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.merchant.entity.OpeningWindow;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.http.HttpStatus;
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
    private final Geocoder geocoder;
    private final DeliveryRadiusCap radiusCap;
    private final ShopApplicationService views;

    ShopProfileService(VendorRepository vendors, Geocoder geocoder, DeliveryRadiusCap radiusCap, ShopApplicationService views) {
        this.vendors = vendors;
        this.geocoder = geocoder;
        this.radiusCap = radiusCap;
        this.views = views;
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
        if (req.addressDetail() != null) {
            vendor.setAddressDetail(req.addressDetail().isBlank() ? null : req.addressDetail().strip());
        }
        if (req.deliveryRadiusKm() != null || req.deliveryFee() != null || req.freeDeliveryThreshold() != null
                || req.minOrderValue() != null || Boolean.TRUE.equals(req.clearFreeDeliveryThreshold())
                || Boolean.TRUE.equals(req.clearMinOrderValue())) {
            // A shop compliant at signup cannot edit its way past a cap lowered since.
            radiusCap.require(req.deliveryRadiusKm());
            vendor.setShipping(
                    req.deliveryRadiusKm() != null ? req.deliveryRadiusKm() : vendor.getDeliveryRadiusKm(),
                    req.deliveryFee() != null ? req.deliveryFee() : vendor.getDeliveryFee(),
                    Boolean.TRUE.equals(req.clearFreeDeliveryThreshold()) ? null
                            : req.freeDeliveryThreshold() != null ? req.freeDeliveryThreshold() : vendor.getFreeDeliveryThreshold(),
                    Boolean.TRUE.equals(req.clearMinOrderValue()) ? null
                            : req.minOrderValue() != null ? req.minOrderValue() : vendor.getMinOrderValue());
        }
        if (req.placeId() != null && !req.placeId().equals(vendor.getPlaceId())) {
            PlaceDetail place = geocoder.placeDetail(req.placeId()).orElseThrow(() -> new BusinessException(
                    HttpStatus.BAD_REQUEST, "ADDRESS_NOT_FOUND", "Không xác định được địa chỉ này. Hãy chọn lại một gợi ý."));
            vendor.setAddress(place.placeId(), place.formattedAddress(), place.ward(), place.province(), place.lat(), place.lng());
            vendor.returnToReview(Instant.now());
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
        vendor.setAcceptingOrders(accepting, owner.actorType(), owner.id(), Instant.now());
        return views.toView(vendors.save(vendor));
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
