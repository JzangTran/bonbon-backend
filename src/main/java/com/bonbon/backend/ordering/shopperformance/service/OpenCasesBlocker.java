package com.bonbon.backend.ordering.shopperformance.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.account.DeletionBlocker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A customer cannot delete the account while a complaint or no-show report about their order is undecided. */
@Component
class OpenCasesBlocker implements DeletionBlocker {

    private final JdbcClient jdbc;

    OpenCasesBlocker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Blocker> blockersOf(UUID userId) {
        long count = jdbc.sql("select count(*) from order_cases where customer_id = :u and status in ('AWAITING_SHOP', 'AWAITING_CUSTOMER', 'OPEN')").param("u", userId).query(Long.class).single();
        return count == 0 ? List.of() : List.of(new Blocker("OPEN_CASES", "Bạn còn khiếu nại hoặc báo cáo về đơn hàng chưa được giải quyết.", "ORDER_CASES", count));
    }
}
