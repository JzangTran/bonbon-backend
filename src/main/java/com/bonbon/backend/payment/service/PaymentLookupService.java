package com.bonbon.backend.payment.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.payment.PaymentLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentLookupService implements PaymentLookup {

    private final JdbcClient jdbc;

    PaymentLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentSummary> ofOrder(UUID orderId) {
        return jdbc.sql("select id, method, provider, status, amount, refunded_amount from payments where order_id = :o").param("o", orderId)
                .query((rs, n) -> {
                    List<PaymentRefund> refunds = jdbc.sql("select reason, amount, status, mode, created_at from payment_refunds where payment_id = :p order by created_at")
                            .param("p", rs.getObject("id", UUID.class))
                            .query((r, i) -> new PaymentRefund(r.getString("reason"), r.getInt("amount"), r.getString("status"), r.getString("mode"),
                                    r.getTimestamp("created_at").toInstant()))
                            .list();
                    return new PaymentSummary(rs.getString("method"), rs.getString("provider"), rs.getString("status"), rs.getInt("amount"),
                            rs.getInt("refunded_amount"), refunds);
                }).optional();
    }
}
