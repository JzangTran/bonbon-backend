package com.bonbon.backend.payment.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.payment.OnlinePayments;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payments row of every order and the MoMo attempts of the online ones (pay-online.md, pay-cod.md). The gateway
 * is called between two short transactions, never inside one: the attempt row is written first, the answer stored after.
 */
@Service
class PaymentService implements OnlinePayments {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final String provider;

    PaymentService(JdbcClient jdbc, PaymentGateway gateway, PlatformTransactionManager transactions,
            @Value("${bonbon.payment.provider:fake}") String provider) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactions);
        this.provider = "momo".equals(provider) ? "MOMO" : "FAKE";
    }

    @Override
    public void recordCashOnDelivery(UUID orderId, int amount) {
        jdbc.sql("insert into payments (order_id, method, amount) values (:o, 'COD', :a) on conflict (order_id) do nothing")
                .param("o", orderId).param("a", amount).update();
    }

    @Override
    public void cashCollected(UUID orderId) {
        jdbc.sql("update payments set status = 'SUCCESS', updated_at = now() where order_id = :o and method = 'COD' and status = 'PENDING'")
                .param("o", orderId).update();
    }

    @Override
    public void orderClosed(UUID orderId) {
        // A payment that already succeeded is not touched here: giving that money back is the refund work.
        int closed = jdbc.sql("update payments set status = 'FAILED', updated_at = now() where order_id = :o and status = 'PENDING'")
                .param("o", orderId).update();
        if (closed > 0) {
            jdbc.sql("""
                    update payment_attempts set status = 'EXPIRED', updated_at = now()
                    where status = 'PENDING' and payment_id = (select id from payments where order_id = :o)""")
                    .param("o", orderId).update();
        }
    }

    @Override
    public Attempt startOnline(UUID orderId, long orderNumber, int amount, Instant expiresAt) {
        Prepared prepared = tx.execute(status -> prepare(orderId, amount, expiresAt));
        PaymentGateway.CreateResult result;
        try {
            result = gateway.create(new PaymentGateway.CreateRequest(prepared.providerOrderId(), prepared.requestId(), amount,
                    "Thanh toan don #" + orderNumber));
        } catch (RuntimeException e) {
            // A timeout or a network error is not the customer's fault: the attempt fails and they may try again.
            log.warn("Creating the payment for order {} failed", orderId, e);
            result = new PaymentGateway.CreateResult(-1, "Gateway unreachable", null, null, null, null);
        }
        PaymentGateway.CreateResult stored = result;
        tx.executeWithoutResult(status -> {
            if (stored.ok()) {
                jdbc.sql("""
                        update payment_attempts set pay_url = :pay, deeplink = :dl, qr_code_url = :qr, result_code = 0, raw_response = :raw,
                               updated_at = now() where id = :id""")
                        .param("pay", stored.payUrl()).param("dl", stored.deeplink()).param("qr", stored.qrCodeUrl())
                        .param("raw", stored.raw()).param("id", prepared.attemptId()).update();
            } else {
                jdbc.sql("""
                        update payment_attempts set status = 'FAILED', result_code = :code, raw_response = :raw, updated_at = now()
                        where id = :id and status = 'PENDING'""")
                        .param("code", stored.resultCode()).param("raw", stored.raw() == null ? stored.message() : stored.raw())
                        .param("id", prepared.attemptId()).update();
            }
        });
        return currentAttempt(orderId).orElseThrow();
    }

    @Override
    public Optional<Attempt> currentAttempt(UUID orderId) {
        return jdbc.sql("""
                select a.attempt_no, a.status, a.pay_url, a.deeplink, a.qr_code_url, a.expires_at
                from payment_attempts a join payments p on p.id = a.payment_id
                where p.order_id = :o order by a.attempt_no desc limit 1""")
                .param("o", orderId)
                .query((rs, n) -> new Attempt(rs.getInt("attempt_no"), rs.getString("status"), rs.getString("pay_url"),
                        rs.getString("deeplink"), rs.getString("qr_code_url"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public void requestLateRefund(UUID paymentId) {
        int queued = jdbc.sql("""
                insert into payment_refunds (payment_id, reason, amount, mode)
                select id, 'LATE_PAYMENT', amount, 'GATEWAY' from payments where id = :p and amount > 0
                on conflict (payment_id, reason) where case_id is null do nothing""")
                .param("p", paymentId).update();
        if (queued > 0) {
            log.info("Payment {} arrived for an order that cannot use it; refund queued", paymentId);
        }
    }

    private Prepared prepare(UUID orderId, int amount, Instant expiresAt) {
        UUID paymentId = jdbc.sql("""
                insert into payments (order_id, method, provider, amount) values (:o, 'ONLINE', :prov, :a)
                on conflict (order_id) do update set provider = excluded.provider
                returning id""")
                .param("o", orderId).param("prov", provider).param("a", amount).query(UUID.class).single();
        String status = jdbc.sql("select status from payments where id = :p").param("p", paymentId).query(String.class).single();
        if ("SUCCESS".equals(status)) {
            throw BusinessException.conflict("ALREADY_PAID", "Đơn này đã được thanh toán.");
        }
        int number = jdbc.sql("select coalesce(max(attempt_no), 0) + 1 from payment_attempts where payment_id = :p")
                .param("p", paymentId).query(Integer.class).single();
        UUID attemptId = UUID.randomUUID();
        String requestId = UUID.randomUUID().toString();
        jdbc.sql("""
                insert into payment_attempts (id, payment_id, attempt_no, provider_order_id, request_id, expires_at)
                values (:id, :p, :n, :po, :rq, :exp)""")
                .param("id", attemptId).param("p", paymentId).param("n", number).param("po", attemptId.toString())
                .param("rq", requestId).param("exp", Timestamp.from(expiresAt)).update();
        return new Prepared(attemptId, attemptId.toString(), requestId);
    }

    private record Prepared(UUID attemptId, String providerOrderId, String requestId) {
    }
}
