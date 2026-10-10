package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** What happened to a case and who did it, in the order it happened. Written in the transaction of the change itself. */
@Component
class CaseLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    CaseLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void add(UUID caseId, String action, ActorType by, UUID actorId, String detail) {
        jdbc.sql("insert into order_case_log (case_id, action, actor_type, actor_id, detail, created_at) values (:c, :a, :t, :i, :d, :at)")
                .param("c", caseId).param("a", action).param("t", by.name()).param("i", actorId).param("d", detail).param("at", Timestamp.from(clock.instant())).update();
    }
}
