package com.bonbon.backend.merchant.service;

import java.math.BigDecimal;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.servicearea.ServiceAreas;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The largest delivery radius a shop may set, checked whenever it saves its radius: in the wizard, at submission, and when an
 * approved shop edits it. A shop in a service area is held to that area's limit, which the platform-wide limit
 * (set-delivery-radius-cap.md) still caps; a shop without an area (approved before areas existed) to the platform-wide limit.
 */
@Component
class DeliveryRadiusCap {

    static final String KEY = "merchant.max_delivery_radius_km";

    private final SystemSettingsService settings;
    private final ServiceAreas areas;

    DeliveryRadiusCap(SystemSettingsService settings, ServiceAreas areas) {
        this.settings = settings;
        this.areas = areas;
    }

    /** The platform-wide limit. */
    BigDecimal maxKm() {
        return new BigDecimal(settings.getString(KEY, "3"));
    }

    /** The limit that applies to this shop: its area's, and never above the platform-wide one. */
    BigDecimal maxKm(Vendor vendor) {
        BigDecimal platform = maxKm();
        return areas.find(vendor.getServiceAreaId()).map(ServiceAreas.Area::maxDeliveryRadiusKm)
                .map(areaMax -> areaMax.min(platform)).orElse(platform);
    }

    void require(Vendor vendor, BigDecimal radiusKm) {
        BigDecimal cap = maxKm(vendor);
        if (radiusKm != null && radiusKm.compareTo(cap) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERY_RADIUS_TOO_LARGE",
                    "Bán kính giao hàng tối đa là " + cap.stripTrailingZeros().toPlainString() + " km"
                            + areas.find(vendor.getServiceAreaId()).map(a -> " ở khu vực " + a.name()).orElse("") + ".")
                    .withProperty("maxDeliveryRadiusKm", cap);
        }
    }
}
