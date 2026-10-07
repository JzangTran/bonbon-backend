package com.bonbon.backend.payment.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.payment.RefundCompleted;
import com.bonbon.backend.payment.RefundNeedsDestination;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queues refunds and sends them back through MoMo (pay-online.md "Refunds"). A request is a row written in the same
 * transaction as the event that causes it; the MoMo call happens after that commits, then the answer is stored. A
 * refund MoMo finally refuses, or one that cannot be tried (API switched off, below the minimum), becomes a manual
 * bank transfer that waits for the customer's account and an admin ({@link RefundDesk}).
 */
@Component
public class PaymentRefunds {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefunds.class);
    static final String REFUND_MODE_KEY = "payment.refund_mode";
    /** MoMo's lowest refund amount. */
    static final int MIN_GATEWAY_AMOUNT = 1_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 50;
    /** "Retry within a short period", "processing": not an answer yet, ask again later with the same orderId. */
    private static final Set<Integer> RETRY = Set.of(1080, 7000, 7002, 9000);
    /** The orderId was already used: MoMo has this refund, so ask what became of it. */
    private static final int DUPLICATE_ORDER_ID = 41;

    /** Published in the queueing transaction; the refund is submitted once it has committed. */
    public record RefundQueued(UUID refundId) {
    }

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final SystemSettingsService settings;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    PaymentRefunds(JdbcClient jdbc, PaymentGateway gateway, SystemSettingsService settings, ApplicationEventPublisher events,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.settings = settings;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Asks for everything still owed on a paid payment. Idempotent per payment and reason: a retry of the same event
     * queues nothing twice. Runs in the caller's transaction.
     */
    public Optional<UUID> queue(UUID paymentId, String reason) {
        boolean manual = "MANUAL".equalsIgnoreCase(settings.getString(REFUND_MODE_KEY, "GATEWAY"));
        UUID id = UUID.randomUUID();
        int queued = jdbc.sql("""
                insert into payment_refunds (id, payment_id, reason, amount, mode, status, provider_order_id)
                select :id, p.id, :reason, p.amount - p.refunded_amount,
                       case when :manual or p.amount - p.refunded_amount < :min then 'MANUAL' else 'GATEWAY' end,
                       case when :manual or p.amount - p.refunded_amount < :min then 'NEEDS_DESTINATION' else 'REQUESTED' end,
                       :idText
                from payments p where p.id = :p and p.status = 'SUCCESS' and p.amount - p.refunded_amount > 0
                on conflict (payment_id, reason) where case_id is null do nothing""")
                .param("id", id).param("reason", reason).param("manual", manual).param("min", MIN_GATEWAY_AMOUNT)
                .param("idText", id.toString()).param("p", paymentId).update();
        if (queued == 0) {
            return Optional.empty();
        }
        log(id, "QUEUED", "SYSTEM", null, reason);
        String mode = jdbc.sql("select mode from payment_refunds where id = :id").param("id", id).query(String.class).single();
        if ("MANUAL".equals(mode)) {
            askForDestination(id);
        } else {
            events.publishEvent(new RefundQueued(id));
        }
        return Optional.of(id);
    }

    /** Submits one refund to MoMo and stores the answer; safe to call twice (only one caller can hold the claim). */
    public void execute(UUID refundId) {
        Claim claim = tx.execute(status -> claim(refundId));
        if (claim == null) {
            return;
        }
        if (claim.purchaseTransId() == null || claim.amount() < MIN_GATEWAY_AMOUNT) {
            tx.executeWithoutResult(status -> toManual(refundId, null, "No MoMo transaction to refund, or below the minimum"));
            return;
        }
        PaymentGateway.RefundResult result;
        try {
            result = gateway.refund(new PaymentGateway.RefundRequest(claim.providerOrderId(), UUID.randomUUID().toString(), claim.amount(),
                    claim.purchaseTransId(), "Hoan tien don #" + claim.orderNumber()));
        } catch (RuntimeException e) {
            log.warn("Refund {} could not reach MoMo", refundId, e);
            result = new PaymentGateway.RefundResult(-1, 0, "unreachable");
        }
        PaymentGateway.RefundResult answer = result.resultCode() == DUPLICATE_ORDER_ID ? resolveDuplicate(claim, result) : result;
        tx.executeWithoutResult(status -> settle(claim, answer));
    }

    /** Submits every gateway refund that is waiting: the ones the commit hook missed and the ones MoMo asked us to retry. */
    public int runDue() {
        List<UUID> due = jdbc.sql("""
                select id from payment_refunds
                where mode = 'GATEWAY' and status in ('REQUESTED', 'PROCESSING') and (claimed_until is null or claimed_until < now())
                order by created_at limit :limit""").param("limit", BATCH).query(UUID.class).list();
        due.forEach(id -> {
            try {
                execute(id);
            } catch (RuntimeException e) {
                log.warn("Refund {} failed unexpectedly", id, e);
            }
        });
        return due.size();
    }

    // --- shared with the desk

    void log(UUID refundId, String action, String actorType, UUID actorId, String detail) {
        jdbc.sql("insert into payment_refund_log (refund_id, action, actor_type, actor_id, detail) values (:r, :a, :t, :i, :d)")
                .param("r", refundId).param("a", action).param("t", actorType).param("i", actorId).param("d", detail).update();
    }

    /** Marks the payment refunded by {@code amount} (fully refunded once the sum reaches what was paid). */
    void applyToPayment(UUID paymentId, int amount) {
        jdbc.sql("""
                update payments set refunded_amount = refunded_amount + :a,
                       status = case when refunded_amount + :a >= amount then 'REFUNDED' else status end, updated_at = now()
                where id = :p""").param("a", amount).param("p", paymentId).update();
    }

    void publishCompleted(UUID refundId) {
        jdbc.sql("""
                select p.order_id, p.order_number, p.customer_id, r.amount, r.mode
                from payment_refunds r join payments p on p.id = r.payment_id where r.id = :id""")
                .param("id", refundId)
                .query((rs, n) -> new RefundCompleted(rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getInt("amount"), rs.getString("mode")))
                .optional().ifPresent(events::publishEvent);
    }

    void askForDestination(UUID refundId) {
        jdbc.sql("""
                select p.order_id, p.order_number, p.customer_id, r.amount
                from payment_refunds r join payments p on p.id = r.payment_id where r.id = :id""")
                .param("id", refundId)
                .query((rs, n) -> new RefundNeedsDestination(rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getInt("amount")))
                .optional().ifPresent(events::publishEvent);
    }

    // --- internals

    private record Claim(UUID paymentId, String providerOrderId, int amount, long orderNumber, Long purchaseTransId, int attempts,
            String purchaseProviderOrderId) {
    }

    /** Takes the refund for a minute, unless someone else holds it; null when there is nothing to do. */
    private Claim claim(UUID refundId) {
        Optional<Claim> found = jdbc.sql("""
                select r.payment_id, r.provider_order_id, r.amount, r.attempts, p.order_number,
                       (select a.provider_trans_id from payment_attempts a where a.payment_id = p.id and a.status = 'SUCCESS'
                        order by a.attempt_no limit 1) as trans_id,
                       (select a.provider_order_id from payment_attempts a where a.payment_id = p.id and a.status = 'SUCCESS'
                        order by a.attempt_no limit 1) as purchase_order_id
                from payment_refunds r join payments p on p.id = r.payment_id
                where r.id = :id and r.mode = 'GATEWAY' and r.status in ('REQUESTED', 'PROCESSING')
                  and (r.claimed_until is null or r.claimed_until < now())
                for update of r skip locked""")
                .param("id", refundId)
                .query((rs, n) -> new Claim(rs.getObject("payment_id", UUID.class), rs.getString("provider_order_id"), rs.getInt("amount"),
                        rs.getLong("order_number"), rs.getObject("trans_id") == null ? null : rs.getLong("trans_id"), rs.getInt("attempts") + 1,
                        rs.getString("purchase_order_id")))
                .optional();
        found.ifPresent(c -> jdbc.sql("""
                update payment_refunds set status = 'PROCESSING', attempts = attempts + 1, claimed_until = now() + interval '1 minute',
                       updated_at = now() where id = :id""").param("id", refundId).update());
        return found.orElse(null);
    }

    /** MoMo says it already has this orderId: its own list of refunds for the payment says whether it went through. */
    private PaymentGateway.RefundResult resolveDuplicate(Claim claim, PaymentGateway.RefundResult original) {
        if (claim.purchaseProviderOrderId() == null) {
            return original;
        }
        try {
            return gateway.query(claim.purchaseProviderOrderId()).refunds().stream()
                    .filter(r -> claim.providerOrderId().equals(r.orderId()))
                    .findFirst()
                    .map(r -> new PaymentGateway.RefundResult(r.resultCode(), r.transId(), "Found in the payment's refund list"))
                    .orElse(new PaymentGateway.RefundResult(1080, 0, "Refund order id already used; not listed yet"));
        } catch (RuntimeException e) {
            return new PaymentGateway.RefundResult(1080, 0, "Could not read the refund list");
        }
    }

    private void settle(Claim claim, PaymentGateway.RefundResult result) {
        UUID refundId = UUID.fromString(claim.providerOrderId());
        int code = result.resultCode();
        if (code == 0) {
            int done = jdbc.sql("""
                    update payment_refunds set status = 'COMPLETED', provider_trans_id = :trans, gateway_result_code = 0,
                           completed_at = now(), claimed_until = null, updated_at = now()
                    where id = :id and status = 'PROCESSING'""").param("trans", result.transId()).param("id", refundId).update();
            if (done > 0) {
                applyToPayment(claim.paymentId(), claim.amount());
                log(refundId, "GATEWAY_COMPLETED", "SYSTEM", null, "transId " + result.transId());
                publishCompleted(refundId);
            }
            return;
        }
        if (code == -1 || RETRY.contains(code)) {
            if (claim.attempts() >= MAX_ATTEMPTS) {
                toManual(refundId, code, "Gave up after " + claim.attempts() + " attempts");
                return;
            }
            // Back off a little more each time; the job picks it up again.
            jdbc.sql("""
                    update payment_refunds set gateway_result_code = :code, claimed_until = now() + (interval '1 minute' * :n), updated_at = now()
                    where id = :id and status = 'PROCESSING'""").param("code", code).param("n", claim.attempts()).param("id", refundId).update();
            log(refundId, "GATEWAY_RETRY", "SYSTEM", null, "code " + code);
            return;
        }
        toManual(refundId, code, result.message());
    }

    /** The gateway cannot do this one: a person returns the money by bank transfer. */
    private void toManual(UUID refundId, Integer code, String why) {
        int changed = jdbc.sql("""
                update payment_refunds set mode = 'MANUAL', status = 'NEEDS_DESTINATION', gateway_result_code = :code, claimed_until = null,
                       updated_at = now() where id = :id and mode = 'GATEWAY' and status in ('REQUESTED', 'PROCESSING')""")
                .param("code", code).param("id", refundId).update();
        if (changed > 0) {
            log(refundId, "TO_MANUAL", "SYSTEM", null, why == null ? "code " + code : "code " + code + ": " + why);
            askForDestination(refundId);
        }
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
