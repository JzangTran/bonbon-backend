package com.bonbon.backend.shopperformance.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: a case the shop did not answer in time goes to the administrators. Off in tests, which call the service with a time they choose. */
@Component
@ConditionalOnProperty(name = "bonbon.shopperformance.cases.enabled", havingValue = "true", matchIfMissing = true)
class OrderCaseTimeoutJob {

    private final ShopCaseService cases;
    private final Clock clock;

    OrderCaseTimeoutJob(ShopCaseService cases, Clock clock) {
        this.cases = cases;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.shopperformance.cases.interval:PT1M}", initialDelayString = "PT55S")
    void run() {
        cases.escalateOverdue(clock.instant());
    }
}
