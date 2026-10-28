package com.bonbon.backend.identity.legal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.identity.legal.DocumentType;
import com.bonbon.backend.identity.legal.LegalDocumentCancelled;
import com.bonbon.backend.identity.legal.LegalDocumentPublished;
import com.bonbon.backend.identity.legal.LegalDocumentReminder;
import com.bonbon.backend.identity.legal.dto.LegalAdminRequests;
import com.bonbon.backend.identity.legal.dto.LegalAdminViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishing a new version of a legal document (flows/legal/publish-legal-document.md): draft, publish with an effective date,
 * or withdraw before it takes effect. A published text is immutable and hashed; a correction is a new version. Whoever changes
 * what a document says is logged. Notifying the affected people is the notification module's job, on the events published here.
 */
@Service
public class LegalAdminService {

    private static final Duration REMINDER_BEFORE = Duration.ofDays(3);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ApplicationEventPublisher events;

    LegalAdminService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public LegalAdminViews.Versions list() {
        return new LegalAdminViews.Versions(jdbc.sql(select() + " where d.language = 'vi' order by d.type, d.version desc").param("now", now()).query((rs, n) -> version(rs)).list());
    }

    @Transactional(readOnly = true)
    public LegalAdminViews.Detail get(UUID id) {
        return detail(id);
    }

    @Transactional
    public LegalAdminViews.Detail createDraft(CurrentPrincipal admin, LegalAdminRequests.CreateDraft request) {
        if (jdbc.sql("select count(*) from legal_documents where type = :t and language = 'vi' and status = 'DRAFT'").param("t", request.type().name()).query(Long.class).single() > 0) {
            throw BusinessException.conflict("DRAFT_EXISTS", "Loại văn bản này đang có một bản nháp; hãy sửa hoặc huỷ bản nháp đó trước.");
        }
        int next = jdbc.sql("select coalesce(max(version), 0) + 1 from legal_documents where type = :t and language = 'vi'").param("t", request.type().name()).query(Integer.class).single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into legal_documents (id, type, language, version, title, summary, content, requires_reacceptance, status, created_by_id)
                values (:id, :t, 'vi', :v, :title, :summary, :content, :material, 'DRAFT', :admin)""")
                .param("id", id).param("t", request.type().name()).param("v", next).param("title", request.title().strip()).param("summary", blankToNull(request.summary()))
                .param("content", request.content().strip()).param("material", request.requiresReacceptance() == null || request.requiresReacceptance()).param("admin", admin.id()).update();
        log(id, "DRAFTED", admin.id(), null);
        return detail(id);
    }

    @Transactional
    public LegalAdminViews.Detail updateDraft(CurrentPrincipal admin, UUID id, LegalAdminRequests.UpdateDraft request) {
        draftOnly(id);
        jdbc.sql("""
                update legal_documents set title = coalesce(:title, title), summary = coalesce(:summary, summary), content = coalesce(:content, content),
                       requires_reacceptance = coalesce(:material, requires_reacceptance) where id = :id""")
                .param("id", id).param("title", blankToNull(request.title())).param("summary", request.summary() == null ? null : request.summary().strip())
                .param("content", blankToNull(request.content())).param("material", request.requiresReacceptance()).update();
        log(id, "EDITED", admin.id(), null);
        return detail(id);
    }

    @Transactional
    public LegalAdminViews.Detail publish(CurrentPrincipal admin, UUID id, LegalAdminRequests.Publish request) {
        draftOnly(id);
        record Row(DocumentType type, int version, String title, String summary, String content, boolean material) {
        }
        Row d = jdbc.sql("select type, version, title, summary, content, requires_reacceptance from legal_documents where id = :id").param("id", id)
                .query((rs, n) -> new Row(DocumentType.valueOf(rs.getString("type")), rs.getInt("version"), rs.getString("title"), rs.getString("summary"), rs.getString("content"),
                        rs.getBoolean("requires_reacceptance"))).single();
        Instant now = clock.instant();
        Instant effective = request == null || request.effectiveAt() == null ? (d.material() ? now.plus(notice()) : now) : request.effectiveAt();
        if (d.material() && effective.isBefore(now.plus(notice()))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EFFECTIVE_TOO_SOON", "Thay đổi quan trọng phải có hiệu lực sau ít nhất " + notice().toDays() + " ngày kể từ lúc đăng.");
        }
        if (effective.isBefore(now.minusSeconds(60))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EFFECTIVE_IN_THE_PAST", "Ngày có hiệu lực không được ở quá khứ.");
        }
        if (jdbc.sql("select count(*) from legal_documents where type = :t and language = 'vi' and status = 'PUBLISHED' and effective_at > :now").param("t", d.type().name())
                .param("now", Timestamp.from(now)).query(Long.class).single() > 0) {
            throw BusinessException.conflict("VERSION_ALREADY_SCHEDULED", "Loại văn bản này đã có một phiên bản đang chờ đến ngày hiệu lực; hãy huỷ nó trước khi đăng bản khác.");
        }
        jdbc.sql("update legal_documents set status = 'PUBLISHED', published_at = :now, effective_at = :effective, content_sha256 = :sha where id = :id")
                .param("now", Timestamp.from(now)).param("effective", Timestamp.from(effective)).param("sha", sha256(d.content())).param("id", id).update();
        log(id, "PUBLISHED", admin.id(), "hiệu lực " + effective);
        events.publishEvent(new LegalDocumentPublished(id, d.type(), d.version(), d.title(), d.summary(), effective, d.material()));
        return detail(id);
    }

    /** A draft is discarded; a published version that has not taken effect is withdrawn and its readers are told. */
    @Transactional
    public LegalAdminViews.Detail cancel(CurrentPrincipal admin, UUID id, LegalAdminRequests.Cancel request) {
        record Row(String status, DocumentType type, int version, String title, Instant effective) {
        }
        Row d = jdbc.sql("select status, type, version, title, effective_at from legal_documents where id = :id").param("id", id)
                .query((rs, n) -> new Row(rs.getString("status"), DocumentType.valueOf(rs.getString("type")), rs.getInt("version"), rs.getString("title"),
                        rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toInstant())).optional().orElseThrow(LegalAdminService::notFound);
        String reason = request == null ? null : blankToNull(request.reason());
        Instant now = clock.instant();
        if (d.status().equals("DRAFT")) {
            jdbc.sql("update legal_documents set status = 'CANCELLED', cancelled_at = :now, cancel_reason = :r where id = :id").param("now", Timestamp.from(now)).param("r", reason).param("id", id).update();
            log(id, "CANCELLED", admin.id(), reason);
            return detail(id);
        }
        if (!d.status().equals("PUBLISHED")) {
            throw BusinessException.conflict("ALREADY_CANCELLED", "Phiên bản này đã bị huỷ.");
        }
        if (!d.effective().isAfter(now)) {
            throw BusinessException.conflict("ALREADY_EFFECTIVE", "Phiên bản đã có hiệu lực; chỉ thay thế được bằng một phiên bản mới hơn.");
        }
        if (reason == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Huỷ phiên bản đã đăng cần ghi lý do.");
        }
        jdbc.sql("update legal_documents set status = 'CANCELLED', cancelled_at = :now, cancel_reason = :r where id = :id").param("now", Timestamp.from(now)).param("r", reason).param("id", id).update();
        log(id, "CANCELLED", admin.id(), reason);
        events.publishEvent(new LegalDocumentCancelled(id, d.type(), d.version(), d.title(), reason));
        return detail(id);
    }

    /** A material change that takes effect within three days gets one last reminder; returns how many documents were reminded. */
    @Transactional
    public int remindDue(Instant now) {
        record Row(UUID id, DocumentType type, int version, String title, Instant effective) {
        }
        List<Row> due = jdbc.sql("""
                select id, type, version, title, effective_at from legal_documents
                where status = 'PUBLISHED' and requires_reacceptance and reminded_at is null and effective_at > :now and effective_at <= :soon""")
                .param("now", Timestamp.from(now)).param("soon", Timestamp.from(now.plus(REMINDER_BEFORE)))
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), DocumentType.valueOf(rs.getString("type")), rs.getInt("version"), rs.getString("title"),
                        rs.getTimestamp("effective_at").toInstant())).list();
        for (Row d : due) {
            jdbc.sql("update legal_documents set reminded_at = :now where id = :id").param("now", Timestamp.from(now)).param("id", d.id()).update();
            events.publishEvent(new LegalDocumentReminder(d.id(), d.type(), d.version(), d.title(), d.effective()));
        }
        return due.size();
    }

    // --- internals

    private String select() {
        return """
                select d.id, d.type, d.version, d.title, d.summary, d.requires_reacceptance, d.published_at, d.effective_at, d.cancelled_at, d.cancel_reason, d.content_sha256,
                       case when d.status = 'DRAFT' then 'DRAFT' when d.status = 'CANCELLED' then 'CANCELLED' when d.effective_at > :now then 'SCHEDULED'
                            when exists (select 1 from legal_documents h where h.type = d.type and h.language = d.language and h.status = 'PUBLISHED' and h.effective_at <= :now and h.version > d.version)
                                 then 'SUPERSEDED' else 'EFFECTIVE' end as shown,
                       case when d.status = 'PUBLISHED' then (select count(distinct c.principal_id) from consent_records c where c.document_id = d.id and c.purpose = 'TERMS' and c.granted) end as accepted,
                       case when d.status = 'PUBLISHED' then
                            (select count(distinct c.principal_id) from consent_records c join legal_documents x on x.id = c.document_id where c.purpose = 'TERMS' and c.granted and x.type = d.type)
                            - (select count(distinct c.principal_id) from consent_records c join legal_documents x on x.id = c.document_id
                               where c.purpose = 'TERMS' and c.granted and x.type = d.type and x.version >= d.version) end as pending
                from legal_documents d""";
    }

    private LegalAdminViews.Version version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LegalAdminViews.Version(rs.getObject("id", UUID.class), DocumentType.valueOf(rs.getString("type")), rs.getInt("version"), rs.getString("title"), rs.getString("summary"),
                rs.getBoolean("requires_reacceptance"), rs.getString("shown"), instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("effective_at")),
                instant(rs.getTimestamp("cancelled_at")), rs.getString("cancel_reason"), rs.getString("content_sha256"), (Long) rs.getObject("accepted"), (Long) rs.getObject("pending"));
    }

    private LegalAdminViews.Detail detail(UUID id) {
        LegalAdminViews.Version v = jdbc.sql(select() + " where d.id = :id").param("now", now()).param("id", id).query((rs, n) -> version(rs)).optional().orElseThrow(LegalAdminService::notFound);
        String content = jdbc.sql("select content from legal_documents where id = :id").param("id", id).query(String.class).single();
        return new LegalAdminViews.Detail(v, content);
    }

    private void draftOnly(UUID id) {
        String status = jdbc.sql("select status from legal_documents where id = :id").param("id", id).query(String.class).optional().orElseThrow(LegalAdminService::notFound);
        if (!status.equals("DRAFT")) {
            throw BusinessException.conflict("NOT_A_DRAFT", "Chỉ sửa hoặc đăng được bản nháp. Văn bản đã đăng không bao giờ sửa; hãy tạo phiên bản mới.");
        }
    }

    private void log(UUID documentId, String action, UUID adminId, String note) {
        jdbc.sql("insert into legal_document_log (document_id, action, admin_id, note, at) values (:d, :a, :admin, :n, :at)")
                .param("d", documentId).param("a", action).param("admin", adminId).param("n", note).param("at", Timestamp.from(clock.instant())).update();
    }

    private Duration notice() {
        return Duration.ofDays(settings.getInt("legal.min_notice_days", 15));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("LEGAL_DOCUMENT_NOT_FOUND", "Không tìm thấy văn bản.");
    }
}
