package com.bonbon.backend.settlement.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.bonbon.backend.settlement.CaseHolds;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CaseHoldService implements CaseHolds {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final LedgerService ledger;

    CaseHoldService(JdbcClient jdbc, Clock clock, LedgerService ledger) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ledger = ledger;
    }

    @Override
    @Transactional
    public void bear(UUID caseId, UUID vendorId, UUID orderId, int refund, int commissionReversal, String note, com.bonbon.backend.common.persistence.ActorType by,
            UUID actorId) {
        ledger.postForCase(vendorId, caseId, orderId, refund, commissionReversal, note, by, actorId);
        release(caseId);
    }

    @Override
    @Transactional
    public void place(UUID caseId, UUID vendorId, int amount) {
        jdbc.sql("insert into settlement_case_holds (case_id, vendor_id, amount, created_at) values (:c, :v, :a, :at) on conflict (case_id) do nothing")
                .param("c", caseId).param("v", vendorId).param("a", amount).param("at", Timestamp.from(clock.instant())).update();
    }

    @Override
    @Transactional
    public void release(UUID caseId) {
        jdbc.sql("update settlement_case_holds set released_at = :at where case_id = :c and released_at is null")
                .param("c", caseId).param("at", Timestamp.from(clock.instant())).update();
    }
}
