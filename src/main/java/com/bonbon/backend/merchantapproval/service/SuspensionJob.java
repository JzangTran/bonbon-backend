package com.bonbon.backend.merchantapproval.service;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every five minutes: suspends the shops whose notice has run out. Off in tests, which call the service with a time they choose. */
@Component
@ConditionalOnProperty(name = "bonbon.merchantapproval.suspensions.enabled", havingValue = "true", matchIfMissing = true)
class SuspensionJob {

    private final SuspensionService suspensions;
    private final Clock clock;

    SuspensionJob(SuspensionService suspensions, Clock clock) {
        this.suspensions = suspensions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bonbon.merchantapproval.suspensions.interval:PT5M}", initialDelayString = "PT57S")
    void run() {
        suspensions.applyDue(clock.instant());
    }
}
