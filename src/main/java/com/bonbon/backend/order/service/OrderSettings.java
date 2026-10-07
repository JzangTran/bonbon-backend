package com.bonbon.backend.order.service;

import java.time.Duration;

import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.stereotype.Component;

/** The order timers (flows/order/README.md "Scheduled jobs"); values are system settings, not constants. */
@Component
class OrderSettings {

    static final String SELLER_RESPONSE_MINUTES = "order.seller_response_minutes";
    static final String HANDOVER_MINUTES = "order.handover_minutes";
    static final String AUTO_DELIVERED_HOURS = "order.auto_delivered_hours";
    static final String PAYMENT_MINUTES = "order.payment_minutes";

    private final SystemSettingsService settings;

    OrderSettings(SystemSettingsService settings) {
        this.settings = settings;
    }

    /** How long a new order may wait for the shop before the system rejects it. */
    Duration sellerResponse() {
        return Duration.ofMinutes(settings.getLong(SELLER_RESPONSE_MINUTES, 10));
    }

    /** How long after confirmation the shop has to hand the food over before the system cancels. */
    Duration handover() {
        return Duration.ofMinutes(settings.getLong(HANDOVER_MINUTES, 90));
    }

    /** How long after leaving the shop an order counts as delivered when nobody says so. */
    Duration autoDelivered() {
        return Duration.ofHours(settings.getLong(AUTO_DELIVERED_HOURS, 3));
    }

    /** How long an online order may wait for its payment before the system cancels it. */
    Duration paymentWindow() {
        return Duration.ofMinutes(settings.getLong(PAYMENT_MINUTES, 15));
    }
}
