package com.bonbon.backend.support.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.messaging.ConversationOpenedByAdmin;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes a row for everything an administrator looks up, in the transaction of the lookup itself. */
@Component
class LookupAudit {

    private final JdbcClient jdbc;
    private final Clock clock;

    LookupAudit(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void record(UUID adminId, String action, String query, UUID subjectId, Integer resultCount, String reason, String reasonNote) {
        write(adminId, action, query, subjectId, resultCount, reason, reasonNote, clock.instant());
    }

    /** An administrator opened a conversation; the messaging module only announces it. */
    @EventListener
    void onConversationOpened(ConversationOpenedByAdmin event) {
        write(event.adminId(), "READ_CONVERSATION", null, event.conversationId(), null, null, null, event.at());
    }

    private void write(UUID adminId, String action, String query, UUID subjectId, Integer resultCount, String reason, String reasonNote, Instant at) {
        jdbc.sql("""
                insert into admin_lookup_audit (admin_id, action, query, subject_id, result_count, reason, reason_note, created_at)
                values (:admin, :action, :query, :subject, :count, :reason, :note, :at)""")
                .param("admin", adminId).param("action", action).param("query", query).param("subject", subjectId).param("count", resultCount)
                .param("reason", reason).param("note", reasonNote).param("at", Timestamp.from(at)).update();
    }
}
