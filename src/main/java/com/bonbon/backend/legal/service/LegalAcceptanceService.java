package com.bonbon.backend.legal.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.dto.LegalAdminViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asking existing users to accept a changed document (flows/legal/accept-legal-documents.md). From the day a material version takes
 * effect, a customer or seller who accepted an older one is held at the gate until they accept the version in force. People who
 * registered after it took effect accepted it then, and a person with no acceptance on record for a kind of document is not
 * asked (every real account has one from registration).
 */
@Service
public class LegalAcceptanceService {

    private static final Duration CLEAR_TTL = Duration.ofSeconds(60);
    private static final String PRINCIPAL_TYPE = "USER";

    private final JdbcClient jdbc;
    private final Clock clock;
    /** Who was found to have nothing pending, until when: one query per minute per person instead of one per request. */
    private final Map<String, Instant> clear = new ConcurrentHashMap<>();

    LegalAcceptanceService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The documents this person must accept before going on; empty when nothing is pending. {@code role} is CUSTOMER or SELLER. */
    @Transactional(readOnly = true)
    public List<LegalAdminViews.Pending> pending(UUID principalId, String role) {
        List<LegalAdminViews.Pending> pending = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());
        for (DocumentType type : typesOf(role)) {
            Integer accepted = jdbc.sql("""
                    select max(d.version) from consent_records c join legal_documents d on d.id = c.document_id
                    where c.principal_id = :p and c.purpose = 'TERMS' and c.granted and d.type = :t""")
                    .param("p", principalId).param("t", type.name()).query(Integer.class).optional().orElse(null);
            if (accepted == null) {
                continue;
            }
            boolean material = jdbc.sql("""
                    select count(*) from legal_documents where type = :t and language = 'vi' and status = 'PUBLISHED' and effective_at <= :now and version > :accepted and requires_reacceptance""")
                    .param("t", type.name()).param("now", now).param("accepted", accepted).query(Long.class).single() > 0;
            if (!material) {
                continue;
            }
            jdbc.sql("""
                    select id, version, title, summary, effective_at from legal_documents
                    where type = :t and language = 'vi' and status = 'PUBLISHED' and published_at <= :now and effective_at <= :now order by version desc limit 1""")
                    .param("t", type.name()).param("now", now)
                    .query((rs, n) -> new LegalAdminViews.Pending(rs.getObject("id", UUID.class), type, rs.getInt("version"), rs.getString("title"), rs.getString("summary"),
                            rs.getTimestamp("effective_at").toInstant())).optional().ifPresent(pending::add);
        }
        return pending;
    }

    /** The gate's cheap check: a person found clear is not asked again for a minute (or until they accept). */
    public List<LegalAdminViews.Pending> pendingCached(UUID principalId, String role) {
        String key = principalId + ":" + role;
        Instant until = clear.get(key);
        if (until != null && until.isAfter(clock.instant())) {
            return List.of();
        }
        List<LegalAdminViews.Pending> pending = pending(principalId, role);
        if (pending.isEmpty()) {
            clear.put(key, clock.instant().plus(CLEAR_TTL));
        }
        return pending;
    }

    /** Records acceptance of exactly the documents that are pending, evidenced like registration is. */
    @Transactional
    public void accept(UUID principalId, String role, Set<UUID> documentIds, ClientContext client) {
        List<LegalAdminViews.Pending> pending = pending(principalId, role);
        if (pending.isEmpty()) {
            return;
        }
        Set<UUID> wanted = pending.stream().map(LegalAdminViews.Pending::id).collect(Collectors.toSet());
        if (!documentIds.containsAll(wanted)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "Bạn cần đồng ý với tất cả văn bản đang chờ.");
        }
        if (!wanted.containsAll(documentIds)) {
            throw new BusinessException(HttpStatus.CONFLICT, "LEGAL_DOCUMENTS_CHANGED", "Văn bản vừa được cập nhật, vui lòng xem lại và đồng ý phiên bản mới.");
        }
        for (UUID id : wanted) {
            jdbc.sql("""
                    insert into consent_records (principal_type, principal_id, purpose, document_id, granted, at, channel, app_version, ip)
                    values (:pt, :p, 'TERMS', :d, true, :at, :channel, :version, :ip)""")
                    .param("pt", PRINCIPAL_TYPE).param("p", principalId).param("d", id).param("at", Timestamp.from(clock.instant()))
                    .param("channel", client.channel()).param("version", client.appVersion()).param("ip", client.ip()).update();
        }
        clear.remove(principalId + ":" + role);
    }

    private static List<DocumentType> typesOf(String role) {
        return "SELLER".equals(role) ? List.of(DocumentType.SELLER_TERMS, DocumentType.PRIVACY_POLICY) : List.of(DocumentType.CUSTOMER_TERMS, DocumentType.PRIVACY_POLICY);
    }
}
