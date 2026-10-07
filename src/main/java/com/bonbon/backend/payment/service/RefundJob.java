package com.bonbon.backend.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every minute: submits refunds the commit hook missed and the ones MoMo asked us to retry. Off in tests. */
@Component
@ConditionalOnProperty(name = "bonbon.payment.reconcile.enabled", havingValue = "true", matchIfMissing = true)
class RefundJob {

    private final PaymentRefunds refunds;

    RefundJob(PaymentRefunds refunds) {
        this.refunds = refunds;
    }

    @Scheduled(fixedDelayString = "${bonbon.payment.reconcile.interval:PT1M}", initialDelayString = "PT50S")
    void run() {
        refunds.runDue();
    }
}
