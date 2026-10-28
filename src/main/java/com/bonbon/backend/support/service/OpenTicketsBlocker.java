package com.bonbon.backend.support.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.account.DeletionBlocker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A person cannot delete the account while a support ticket of theirs is not closed. */
@Component
class OpenTicketsBlocker implements DeletionBlocker {

    private final JdbcClient jdbc;

    OpenTicketsBlocker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Blocker> blockersOf(UUID userId) {
        long count = jdbc.sql("select count(*) from support_tickets where user_id = :u and status in ('OPEN', 'ANSWERED')").param("u", userId).query(Long.class).single();
        return count == 0 ? List.of() : List.of(new Blocker("OPEN_TICKETS", "Bạn còn phiếu hỗ trợ chưa đóng. Hãy đóng phiếu trước.", "SUPPORT_TICKETS", count));
    }
}
