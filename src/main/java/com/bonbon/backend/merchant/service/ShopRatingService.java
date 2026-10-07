package com.bonbon.backend.merchant.service;

import java.util.UUID;
import java.util.function.Supplier;

import com.bonbon.backend.merchant.ShopRatings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShopRatingService implements ShopRatings {

    private final JdbcClient jdbc;

    ShopRatingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(UUID vendorId, Supplier<RatingTotals> totals) {
        // The lock comes first: the totals are read by a later statement, which sees what the previous holder committed.
        jdbc.sql("select id from vendors where id = :id for update").param("id", vendorId).query(UUID.class).optional();
        RatingTotals t = totals.get();
        jdbc.sql("update vendors set rating_sum = :sum, rating_count = :count where id = :id")
                .param("sum", t.sum()).param("count", t.count()).param("id", vendorId).update();
    }
}
