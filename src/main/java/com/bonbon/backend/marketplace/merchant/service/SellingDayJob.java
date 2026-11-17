package com.bonbon.backend.marketplace.merchant.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every minute: dishes sold out "for today" whose time is up go back on sale, and a timed pause in taking orders ends. Both are
 * already treated as over by the entity in between runs; this keeps the stored state and the SQL-based browsing in step.
 */
@Component
@ConditionalOnProperty(name = "bonbon.merchant.selling-day.enabled", havingValue = "true", matchIfMissing = true)
public class SellingDayJob {

    private final JdbcClient jdbc;

    SellingDayJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${bonbon.merchant.selling-day.interval:PT1M}", initialDelayString = "PT1M")
    @Transactional
    public void run() {
        jdbc.sql("""
                update marketplace.menu_items set status = 'AVAILABLE', sold_out_until = null, updated_at = now()
                where status = 'SOLD_OUT' and sold_out_until is not null and sold_out_until <= now()""").update();
        jdbc.sql("""
                update marketplace.vendors set accepting_orders = true, paused_until = null, pause_reason = null,
                       accepting_orders_changed_at = now(), accepting_orders_changed_by_type = 'SYSTEM', accepting_orders_changed_by_id = null
                where accepting_orders = false and paused_until is not null and paused_until <= now()""").update();
    }
}
