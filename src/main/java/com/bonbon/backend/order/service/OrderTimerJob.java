package com.bonbon.backend.order.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OrderTimers} every minute: a nightly job would stretch a 90-minute timer to hours. Off in tests
 * ({@code bonbon.order.timers.enabled=false}), which call the timers with a time of their own choosing.
 */
@Component
@ConditionalOnProperty(name = "bonbon.order.timers.enabled", havingValue = "true", matchIfMissing = true)
class OrderTimerJob {

    private final OrderTimers timers;

    OrderTimerJob(OrderTimers timers) {
        this.timers = timers;
    }

    @Scheduled(fixedDelayString = "${bonbon.order.timers.interval:PT1M}", initialDelayString = "PT30S")
    void run() {
        timers.runOnce(Instant.now());
    }
}
