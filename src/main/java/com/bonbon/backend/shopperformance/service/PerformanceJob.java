package com.bonbon.backend.shopperformance.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every day shortly after midnight, Vietnam time: evaluates the week that last closed (a repeat changes nothing, which is also
 * how a missed Monday is caught) and moves every shop along. Off in tests, which call the service with a time they choose.
 */
@Component
@ConditionalOnProperty(name = "bonbon.shopperformance.performance.enabled", havingValue = "true", matchIfMissing = true)
class PerformanceJob {

    private final PerformanceService performance;
    private final Clock clock;

    PerformanceJob(PerformanceService performance, Clock clock) {
        this.performance = performance;
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Ho_Chi_Minh")
    void run() {
        performance.runDaily(clock.instant());
    }
}
