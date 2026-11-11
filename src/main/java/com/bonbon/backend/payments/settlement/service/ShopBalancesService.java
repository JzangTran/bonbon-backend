package com.bonbon.backend.payments.settlement.service;

import java.util.UUID;

import com.bonbon.backend.payments.settlement.ShopBalances;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShopBalancesService implements ShopBalances {

    private final JdbcClient jdbc;

    ShopBalancesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public long balance(UUID vendorId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    @Override
    @Transactional(readOnly = true)
    public long heldForCases(UUID vendorId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from settlement_case_holds where vendor_id = :v and released_at is null").param("v", vendorId).query(Long.class).single();
    }
}
