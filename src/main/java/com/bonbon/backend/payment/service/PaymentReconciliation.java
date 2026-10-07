package com.bonbon.backend.payment.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks MoMo about attempts whose IPN never came (pay-online.md "Reconciliation"). MoMo documents the query as the way
 * to learn a result without an IPN, and its IPN retry schedule is not documented, so this job is the safety net. An
 * attempt that is still undecided after its deadline becomes {@code EXPIRED}; a success found later is still recorded.
 */
@Component
public class PaymentReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);
    static final String RECONCILE_AFTER_MINUTES = "payment.reconcile_after_minutes";
    private static final int BATCH = 100;

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final PaymentOutcomes outcomes;
    private final SystemSettingsService settings;
    private final TransactionTemplate tx;

    PaymentReconciliation(JdbcClient jdbc, PaymentGateway gateway, PaymentOutcomes outcomes, SystemSettingsService settings,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.outcomes = outcomes;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactions);
    }

    private record Pending(String providerOrderId, Instant expiresAt) {
    }

    /** Checks every attempt that has waited long enough, as if it were {@code now}; returns how many changed. */
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(Duration.ofMinutes(settings.getLong(RECONCILE_AFTER_MINUTES, 2)));
        List<Pending> pending = jdbc.sql("""
                select provider_order_id, expires_at from payment_attempts
                where status = 'PENDING' and pay_url is not null and created_at < :cutoff order by created_at limit :limit""")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", BATCH)
                .query((rs, n) -> new Pending(rs.getString("provider_order_id"), rs.getTimestamp("expires_at").toInstant())).list();
        int changed = 0;
        for (Pending attempt : pending) {
            try {
                PaymentGateway.QueryResult answer = gateway.query(attempt.providerOrderId());
                Boolean moved = tx.execute(status -> {
                    var target = outcomes.lock(attempt.providerOrderId()).orElse(null);
                    if (target == null) {
                        return false;
                    }
                    if (answer.amount() != 0 && answer.amount() != target.amount()) {
                        log.warn("Query amount {} differs from {} for {}", answer.amount(), target.amount(), attempt.providerOrderId());
                        return false;
                    }
                    boolean applied = outcomes.apply(target, answer.resultCode(), answer.transId(), answer.payType());
                    if (!applied && now.isAfter(attempt.expiresAt())) {
                        return jdbc.sql("update payment_attempts set status = 'EXPIRED', updated_at = now() where id = :id and status = 'PENDING'")
                                .param("id", target.attemptId()).update() > 0;
                    }
                    return applied;
                });
                if (Boolean.TRUE.equals(moved)) {
                    changed++;
                }
            } catch (RuntimeException e) {
                // MoMo being slow or down must not stop the other attempts; the next run asks again.
                log.warn("Reconciling attempt {} failed", attempt.providerOrderId(), e);
            }
        }
        if (changed > 0) {
            log.info("Payment reconciliation changed {} attempts", changed);
        }
        return changed;
    }
}
