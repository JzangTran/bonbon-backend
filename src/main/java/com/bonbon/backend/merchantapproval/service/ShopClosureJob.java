package com.bonbon.backend.merchantapproval.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: a shop that asked to close and has nothing left to settle becomes CLOSED. Off in tests, which call the service themselves. */
@Component
@ConditionalOnProperty(name = "bonbon.merchantapproval.closures.enabled", havingValue = "true", matchIfMissing = true)
class ShopClosureJob {

    private final ShopClosureService closures;

    ShopClosureJob(ShopClosureService closures) {
        this.closures = closures;
    }

    @Scheduled(fixedDelayString = "${bonbon.merchantapproval.closures.interval:PT1H}", initialDelayString = "PT6M")
    void run() {
        closures.finishReady();
    }
}
