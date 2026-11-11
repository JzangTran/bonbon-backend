package com.bonbon.backend.marketplace.merchant.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.bonbon.backend.marketplace.merchant.VendorStatus;
import com.bonbon.backend.marketplace.merchant.entity.OpeningWindow;
import com.bonbon.backend.marketplace.merchant.entity.Vendor;

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
                && !vendor.getCommissionStage().pausesOrders() && withinHours(vendor.getOpeningHours(), now);
    }

    /** When the window open now ends, as a minute of the week; empty outside every window. */
    static java.util.OptionalInt closingMinute(List<OpeningWindow> windows, ZonedDateTime now) {
        int minute = minuteOfWeek(now);
        return windows.stream()
                .filter(w -> (minute >= w.startMinute() && minute < w.endMinute())
                        || (minute + WEEK_MINUTES >= w.startMinute() && minute + WEEK_MINUTES < w.endMinute()))
                .mapToInt(OpeningWindow::endMinute).findFirst();
    }

    /** The next window to start after now, as a minute of the week; empty when the shop has none. */
    static java.util.OptionalInt nextOpeningMinute(List<OpeningWindow> windows, ZonedDateTime now) {
        int minute = minuteOfWeek(now);
        return windows.stream().mapToInt(OpeningWindow::startMinute).boxed()
                .min(java.util.Comparator.comparingInt(start -> Math.floorMod(start - minute - 1, WEEK_MINUTES)))
                .map(java.util.OptionalInt::of).orElse(java.util.OptionalInt.empty());
    }

    private static int minuteOfWeek(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(VIETNAM);
        return (local.getDayOfWeek().getValue() - 1) * 1440 + local.getHour() * 60 + local.getMinute();
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
