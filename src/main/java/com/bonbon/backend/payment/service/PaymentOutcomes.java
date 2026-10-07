package com.bonbon.backend.payment.service;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.payment.PaymentConfirmed;
import com.bonbon.backend.payment.OnlinePayments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a MoMo result does to our records, the same whether it came from the signed IPN or from our own query: the
 * IPN handler and the reconciliation job both end here, and every step is idempotent (pay-online.md).
 */
@Component
class PaymentOutcomes {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomes.class);
    /** MoMo result codes that mean "not decided yet": initiated, waiting for the customer, being processed. */
    private static final Set<Integer> PENDING = Set.of(1000, 7000, 7002, 9000);

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final OnlinePayments payments;

    PaymentOutcomes(JdbcClient jdbc, ApplicationEventPublisher events, OnlinePayments payments) {
        this.jdbc = jdbc;
        this.events = events;
        this.payments = payments;
    }

    /** The attempt and its payment, locked so a duplicate IPN and the reconciliation job cannot both act. */
    record Target(UUID attemptId, UUID paymentId, UUID orderId, int amount, String attemptStatus) {
    }

    @Transactional
    public java.util.Optional<Target> lock(String providerOrderId) {
        return jdbc.sql("""
                select a.id as attempt_id, p.id as payment_id, p.order_id, p.amount, a.status
                from payment_attempts a join payments p on p.id = a.payment_id
                where a.provider_order_id = :po for update of a""")
                .param("po", providerOrderId)
                .query((rs, n) -> new Target(rs.getObject("attempt_id", UUID.class), rs.getObject("payment_id", UUID.class),
                        rs.getObject("order_id", UUID.class), rs.getInt("amount"), rs.getString("status")))
                .optional();
    }

    /**
     * Applies one result to an attempt found (and locked) by {@link #lock}; must run in the same transaction.
     * Returns true when this call changed something.
     */
    @Transactional
    public boolean apply(Target target, int resultCode, long transId, String payType) {
        if (resultCode == 0) {
            return succeed(target, resultCode, transId, payType);
        }
        if (PENDING.contains(resultCode)) {
            return false;
        }
        // Any other code is a final "no": insufficient funds, expired, denied by the customer...
        return jdbc.sql("""
                update payment_attempts set status = 'FAILED', result_code = :code, pay_type = :type, updated_at = now()
                where id = :id and status = 'PENDING'""")
                .param("code", resultCode).param("type", payType).param("id", target.attemptId()).update() > 0;
    }

    private boolean succeed(Target target, int resultCode, long transId, String payType) {
        // PENDING and EXPIRED may still become SUCCESS: an expired link that was paid anyway is money that arrived.
        int marked = jdbc.sql("""
                update payment_attempts set status = 'SUCCESS', result_code = :code, provider_trans_id = :trans, pay_type = :type,
                       updated_at = now() where id = :id and status in ('PENDING', 'EXPIRED')""")
                .param("code", resultCode).param("trans", transId).param("type", payType).param("id", target.attemptId()).update();
        if (marked == 0) {
            return false;
        }
        int paid = jdbc.sql("""
                update payments set status = 'SUCCESS', updated_at = now()
                where id = :p and status in ('PENDING', 'FAILED')""")
                .param("p", target.paymentId()).update();
        if (paid > 0) {
            events.publishEvent(new PaymentConfirmed(target.orderId(), target.paymentId(), target.amount()));
        } else {
            // A second attempt of an already paid order also went through: the customer paid twice.
            log.error("Payment {} was paid again by attempt {}; refund queued", target.paymentId(), target.attemptId());
            payments.requestLateRefund(target.paymentId());
        }
        return true;
    }
}
