package com.bonbon.backend.merchant.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.OpeningWindow;
import com.bonbon.backend.merchant.entity.Vendor;

/**
 * The one rule for whether a shop takes orders now (pause-orders.md): approved, accepting orders, and the
 * current Vietnam time inside one of its windows. A window past midnight belongs to the day it starts on.
 */
final class ShopHours {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int WEEK_MINUTES = 7 * 1440;

    private ShopHours() {
    }

    static boolean isOpen(Vendor vendor, ZonedDateTime now) {
        return vendor.getStatus() == VendorStatus.APPROVED && vendor.isAcceptingOrders()
                && withinHours(vendor.getOpeningHours(), now);
    }

    static boolean withinHours(List<OpeningWindow> windows, ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(VIETNAM);
        int minute = (local.getDayOfWeek().getValue() - 1) * 1440 + local.getHour() * 60 + local.getMinute();
        for (OpeningWindow w : windows) {
            int start = w.startMinute();
            int end = w.endMinute();
            // A Sunday window past midnight also covers the start of Monday.
            if ((minute >= start && minute < end) || (minute + WEEK_MINUTES >= start && minute + WEEK_MINUTES < end)) {
                return true;
            }
        }
        return false;
    }
}
