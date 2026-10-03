package com.bonbon.backend.merchant.service;

import java.math.BigDecimal;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The platform-wide maximum delivery radius (set-delivery-radius-cap.md), checked whenever a shop saves its
 * radius: in the wizard, at submission, and when an approved shop edits it.
 */
@Component
class DeliveryRadiusCap {

    static final String KEY = "merchant.max_delivery_radius_km";

    private final SystemSettingsService settings;

    DeliveryRadiusCap(SystemSettingsService settings) {
        this.settings = settings;
    }

    BigDecimal maxKm() {
        return new BigDecimal(settings.getString(KEY, "3"));
    }

    void require(BigDecimal radiusKm) {
        BigDecimal cap = maxKm();
        if (radiusKm != null && radiusKm.compareTo(cap) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERY_RADIUS_TOO_LARGE",
                    "Bán kính giao hàng tối đa là " + cap.stripTrailingZeros().toPlainString() + " km.")
                    .withProperty("maxDeliveryRadiusKm", cap);
        }
    }
}
