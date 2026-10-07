package com.bonbon.backend.payment.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Submits a refund to MoMo right after the transaction that queued it commits, so the customer does not wait for the
 * next job run. Off in tests ({@code bonbon.payment.refund.immediate=false}), which run the refunds themselves.
 */
@Component
@ConditionalOnProperty(name = "bonbon.payment.refund.immediate", havingValue = "true", matchIfMissing = true)
class RefundTrigger {

    private final PaymentRefunds refunds;

    RefundTrigger(PaymentRefunds refunds) {
        this.refunds = refunds;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueued(PaymentRefunds.RefundQueued event) {
        refunds.execute(event.refundId());
    }
}
