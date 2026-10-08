package com.bonbon.backend.merchant.service;

import java.util.UUID;

import com.bonbon.backend.merchant.ShopPerformanceStanding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Written with SQL, not the entity: the weekly job changes it while a seller may be editing the same shop. */
@Service
class ShopPerformanceStandingService implements ShopPerformanceStanding {

    private final JdbcClient jdbc;

    ShopPerformanceStandingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void setRestricted(UUID vendorId, boolean restricted) {
        jdbc.sql("update vendors set performance_restricted = :r where id = :id").param("r", restricted).param("id", vendorId).update();
    }
}
