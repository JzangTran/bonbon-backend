package com.bonbon.backend.payment.service;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link PaymentReconciliation} every minute; off in tests, which call it with a time of their own. */
@Component
@ConditionalOnProperty(name = "bonbon.payment.reconcile.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconciliationJob {

    private final PaymentReconciliation reconciliation;

    PaymentReconciliationJob(PaymentReconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${bonbon.payment.reconcile.interval:PT1M}", initialDelayString = "PT45S")
    void run() {
        reconciliation.runOnce(Instant.now());
    }
}
