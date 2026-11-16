package com.bonbon.backend.communication.messaging.service;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.communication.messaging.dto.ChatViews;
import com.bonbon.backend.support.ReportedMessages;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The moderation side of chat (review-reports.md, send-message.md "Moderation"): evidence for a reported message, hiding it, stopping
 * a sender for a while, and the word list masked as *** in what the other side reads. The word list is matched on letters without
 * accents too, because most people type chat without them; it is a minimum filter, not automatic moderation.
 */
@Service
public class MessageModerationService implements ReportedMessages {

    private static final Duration TERMS_CACHE = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile List<String> terms = List.of();
    private volatile Instant termsLoadedAt = Instant.EPOCH;

    MessageModerationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // --- evidence and actions for reporting

    @Override
    @Transactional(readOnly = true)
    public Optional<Evidence> evidence(UUID messageId, int context) {
        record Head(UUID conversationId, UUID customerId, UUID vendorId, MessageSnapshot message) {
        }
        Optional<Head> head = jdbc.sql("""
                select m.id, m.sender_type, m.sender_id, m.text, m.image_key, m.created_at, c.id as conversation_id, c.customer_id, c.vendor_id
                from communication.messages m join communication.conversations c on c.id = m.conversation_id where m.id = :id""")
                .param("id", messageId)
                .query((rs, n) -> new Head(rs.getObject("conversation_id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getObject("vendor_id", UUID.class), snapshot(rs)))
                .optional();
        return head.map(h -> {
            List<MessageSnapshot> before = new ArrayList<>(jdbc.sql("""
                    select id, sender_type, sender_id, text, image_key, created_at from communication.messages
                    where conversation_id = :c and (created_at, id) < (:at, :id) order by created_at desc, id desc limit :n""")
                    .param("c", h.conversationId()).param("at", Timestamp.from(h.message().createdAt())).param("id", messageId).param("n", context)
                    .query((rs, n) -> snapshot(rs)).list());
            Collections.reverse(before);
            List<MessageSnapshot> after = jdbc.sql("""
                    select id, sender_type, sender_id, text, image_key, created_at from communication.messages
                    where conversation_id = :c and (created_at, id) > (:at, :id) order by created_at, id limit :n""")
                    .param("c", h.conversationId()).param("at", Timestamp.from(h.message().createdAt())).param("id", messageId).param("n", context)
                    .query((rs, n) -> snapshot(rs)).list();
            return new Evidence(h.conversationId(), h.customerId(), h.vendorId(), h.message(), before, after);
        });
    }

    @Override
    @Transactional
    public void hide(UUID messageId, String reason) {
        jdbc.sql("update communication.messages set hidden_at = coalesce(hidden_at, :now), hidden_reason = :reason where id = :id")
                .param("now", Timestamp.from(clock.instant())).param("reason", reason).param("id", messageId).update();
    }

    @Override
    @Transactional
    public void restrict(UUID userId, Instant until, String reason, UUID reportId, UUID adminId) {
        jdbc.sql("""
                insert into communication.messaging_restrictions (user_id, restricted_until, reason, report_id, created_by, created_at)
                values (:user, :until, :reason, :report, :admin, :now)
                on conflict (user_id) do update set restricted_until = greatest(messaging_restrictions.restricted_until, excluded.restricted_until),
                    reason = excluded.reason, report_id = excluded.report_id, created_by = excluded.created_by, created_at = excluded.created_at""")
                .param("user", userId).param("until", Timestamp.from(until)).param("reason", reason).param("report", reportId)
                .param("admin", adminId).param("now", Timestamp.from(clock.instant())).update();
    }

    @Override
    @Transactional
    public void unhide(UUID messageId) {
        jdbc.sql("update communication.messages set hidden_at = null, hidden_reason = null where id = :id").param("id", messageId).update();
    }

    @Override
    @Transactional
    public void liftRestriction(UUID userId, UUID reportId) {
        jdbc.sql("delete from communication.messaging_restrictions where user_id = :u and report_id = :r").param("u", userId).param("r", reportId).update();
    }

    /** Until when this person may not send, if they are restricted now. */
    @Transactional(readOnly = true)
    public Optional<Instant> restrictedUntil(UUID userId) {
        return jdbc.sql("select restricted_until from communication.messaging_restrictions where user_id = :u and restricted_until > :now")
                .param("u", userId).param("now", Timestamp.from(clock.instant())).query(Timestamp.class).optional().map(Timestamp::toInstant);
    }

    // --- the word list

    @Transactional(readOnly = true)
    public List<ChatViews.ModerationTerm> listTerms() {
        return jdbc.sql("select id, term, active, created_at from communication.moderation_terms order by lower(term)")
                .query((rs, n) -> new ChatViews.ModerationTerm(rs.getObject("id", UUID.class), rs.getString("term"), rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public ChatViews.ModerationTerm addTerm(UUID adminId, String term) {
        String t = term == null ? "" : term.strip().replaceAll("\\s+", " ");
        if (t.length() < 2 || t.length() > 50) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TERM_INVALID", "Từ cần chặn dài 2–50 ký tự.");
        }
        if (jdbc.sql("select count(*) from communication.moderation_terms where lower(term) = lower(:t)").param("t", t).query(Long.class).single() > 0) {
            throw BusinessException.conflict("TERM_EXISTS", "Từ này đã có trong danh sách.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("insert into communication.moderation_terms (id, term, active, created_by, created_at) values (:id, :t, true, :by, :now)")
                .param("id", id).param("t", t).param("by", adminId).param("now", Timestamp.from(now)).update();
        termsLoadedAt = Instant.EPOCH;
        return new ChatViews.ModerationTerm(id, t, true, now);
    }

    @Transactional
    public void setTermActive(UUID id, boolean active) {
        if (jdbc.sql("update communication.moderation_terms set active = :a where id = :id").param("a", active).param("id", id).update() == 0) {
            throw BusinessException.notFound("TERM_NOT_FOUND", "Không tìm thấy từ này.");
        }
        termsLoadedAt = Instant.EPOCH;
    }

    @Transactional
    public void deleteTerm(UUID id) {
        if (jdbc.sql("delete from communication.moderation_terms where id = :id").param("id", id).update() == 0) {
            throw BusinessException.notFound("TERM_NOT_FOUND", "Không tìm thấy từ này.");
        }
        termsLoadedAt = Instant.EPOCH;
    }

    /** The text as the other side reads it: each listed word or phrase, with or without accents, replaced by stars. */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> active = activeTerms();
        if (active.isEmpty()) {
            return text;
        }
        String folded = fold(text);
        char[] out = text.toCharArray();
        for (String term : active) {
            int from = 0;
            while ((from = folded.indexOf(term, from)) >= 0) {
                int end = from + term.length();
                boolean startOk = from == 0 || !Character.isLetterOrDigit(folded.charAt(from - 1));
                boolean endOk = end == folded.length() || !Character.isLetterOrDigit(folded.charAt(end));
                if (startOk && endOk) {
                    for (int i = from; i < end; i++) {
                        if (!Character.isWhitespace(out[i])) {
                            out[i] = '*';
                        }
                    }
                }
                from = end;
            }
        }
        return new String(out);
    }

    private List<String> activeTerms() {
        Instant now = clock.instant();
        if (Duration.between(termsLoadedAt, now).compareTo(TERMS_CACHE) > 0) {
            terms = jdbc.sql("select term from communication.moderation_terms where active").query(String.class).list().stream().map(MessageModerationService::fold)
                    .filter(t -> !t.isBlank()).toList();
            termsLoadedAt = now;
        }
        return terms;
    }

    /** Lower case without accents, one character for one character, so positions in the folded text are positions in the original. */
    static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'đ' || c == 'Đ') {
                sb.append('d');
                continue;
            }
            String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            sb.append(base.isEmpty() ? c : Character.toLowerCase(base.charAt(0)));
        }
        return sb.toString();
    }

    private static MessageSnapshot snapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MessageSnapshot(rs.getObject("id", UUID.class), rs.getString("sender_type"), rs.getObject("sender_id", UUID.class),
                rs.getString("text"), rs.getString("image_key"), rs.getTimestamp("created_at").toInstant());
    }
}
