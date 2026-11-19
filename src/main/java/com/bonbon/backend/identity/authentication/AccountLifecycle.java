package com.bonbon.backend.identity.authentication;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What is left of account deletion. The feature was removed (owner decision 2026-11-19): deleting an account breaks the history that
 * orders, payments, settlement and reviews rely on, so accounts are never deleted. Rows the old feature left PENDING_DELETION become
 * active again on the next sign-in.
 */
@Service
public class AccountLifecycle {

    private final JdbcClient jdbc;

    AccountLifecycle(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** True when a row left pending by the removed deletion feature was made active again. */
    @Transactional
    public boolean restoreIfPending(UUID userId) {
        return jdbc.sql("update users set status = 'ACTIVE', deletion_requested_at = null, deletion_scheduled_at = null where id = :id and status = 'PENDING_DELETION'")
                .param("id", userId).update() > 0;
    }
}
