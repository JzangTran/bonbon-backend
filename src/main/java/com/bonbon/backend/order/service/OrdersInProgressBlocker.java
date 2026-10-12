package com.bonbon.backend.order.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.account.DeletionBlocker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A customer cannot delete the account while an order of theirs is still going. */
@Component
class OrdersInProgressBlocker implements DeletionBlocker {

    private final JdbcClient jdbc;

    OrdersInProgressBlocker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Blocker> blockersOf(UUID userId) {
        long count = jdbc.sql("select count(*) from orders where customer_id = :u and status in ('PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PREPARING', 'OUT_FOR_DELIVERY')").param("u", userId).query(Long.class).single();
        return count == 0 ? List.of() : List.of(new Blocker("ORDERS_IN_PROGRESS", "Bạn còn đơn hàng đang xử lý. Hãy đợi đơn hoàn tất hoặc huỷ đơn.", "ORDERS", count));
    }
}
