package com.bonbon.backend.shopperformance.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.shopperformance.OrderCaseLookup;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderCaseLookupService implements OrderCaseLookup {

    private final JdbcClient jdbc;

    OrderCaseLookupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseRef> ofOrder(UUID orderId) {
        return jdbc.sql("select id, type, status, refund_amount, opened_at from order_cases where order_id = :o order by opened_at, id").param("o", orderId)
                .query((rs, n) -> new CaseRef(rs.getObject("id", UUID.class), rs.getString("type"), rs.getString("status"), rs.getInt("refund_amount"),
                        rs.getTimestamp("opened_at").toInstant()))
                .list();
    }
}
