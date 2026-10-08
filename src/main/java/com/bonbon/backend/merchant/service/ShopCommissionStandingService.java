package com.bonbon.backend.merchant.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.merchant.ShopCommissionStanding;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Written with SQL, not the entity: settlement changes it while a seller may be editing the same shop. */
@Service
class ShopCommissionStandingService implements ShopCommissionStanding {

    private final JdbcClient jdbc;

    ShopCommissionStandingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void set(UUID vendorId, Stage stage, Instant overdueSince) {
        jdbc.sql("update vendors set commission_stage = :stage, commission_overdue_since = :since where id = :id")
                .param("stage", stage.name()).param("since", overdueSince == null ? null : Timestamp.from(overdueSince)).param("id", vendorId).update();
    }

    @Override
    @Transactional(readOnly = true)
    public Standing of(UUID vendorId) {
        return jdbc.sql("select commission_stage, commission_overdue_since from vendors where id = :id").param("id", vendorId)
                .query((rs, n) -> new Standing(Stage.valueOf(rs.getString("commission_stage")),
                        rs.getTimestamp("commission_overdue_since") == null ? null : rs.getTimestamp("commission_overdue_since").toInstant()))
                .optional().orElse(new Standing(Stage.NONE, null));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Stage> stages(Collection<UUID> vendorIds) {
        Map<UUID, Stage> result = new HashMap<>();
        if (!vendorIds.isEmpty()) {
            jdbc.sql("select id, commission_stage from vendors where id in (:ids)").param("ids", vendorIds)
                    .query((rs, n) -> result.put(rs.getObject("id", UUID.class), Stage.valueOf(rs.getString("commission_stage")))).list();
        }
        return result;
    }
}
