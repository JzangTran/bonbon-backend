package com.bonbon.backend.shopperformance.service;

import java.util.UUID;

import com.bonbon.backend.shopperformance.ShopCaseStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShopCaseStatusService implements ShopCaseStatus {

    private final JdbcClient jdbc;

    ShopCaseStatusService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public long openCases(UUID vendorId) {
        return jdbc.sql("select count(*) from order_cases where vendor_id = :v and status in ('AWAITING_SHOP', 'AWAITING_CUSTOMER', 'OPEN')").param("v", vendorId).query(Long.class).single();
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingAppeals(UUID vendorId) {
        return jdbc.sql("select count(*) from shop_penalties where vendor_id = :v and appeal_status = 'PENDING'").param("v", vendorId).query(Long.class).single();
    }
}
