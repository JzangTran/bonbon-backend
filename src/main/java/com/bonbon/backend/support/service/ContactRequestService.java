package com.bonbon.backend.support.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.bonbon.backend.identity.authentication.UserNames;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.ContactRequestAnswered;
import com.bonbon.backend.support.dto.ContactRequests;
import com.bonbon.backend.support.dto.ContactViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public contact form of the help site: anyone writes with a name and an email, signed in or not; an administrator answers and
 * the answer goes to that email. The form always carries a captcha, and an address or an email can only send a few a day, so the
 * inbox cannot be flooded.
 */
@Service
public class ContactRequestService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int PREVIEW = 160;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateLimiter limiter;
    private final CaptchaVerifier captcha;
    private final UserNames userNames;
    private final ApplicationEventPublisher events;

    ContactRequestService(JdbcClient jdbc, Clock clock, RateLimiter limiter, CaptchaVerifier captcha, UserNames userNames, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limiter = limiter;
        this.captcha = captcha;
        this.userNames = userNames;
        this.events = events;
    }

    @Transactional
    public ContactViews.Receipt submit(ContactRequests.Submit request, String ip) {
        if (!captcha.isValid(request.captchaToken(), ip)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "Hãy xác nhận bạn không phải robot rồi gửi lại.");
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        if (!limiter.tryAcquire("contact-ip:" + ip, 5, Duration.ofHours(1)) || !limiter.tryAcquire("contact-email:" + email, 3, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CONTACT_REQUESTS", "Bạn đã gửi nhiều yêu cầu; đội hỗ trợ sẽ trả lời qua email, hãy đợi một chút.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                insert into contact_requests (id, full_name, email, role, topic, message, status, created_at, updated_at)
                values (:id, :n, :e, :r, :t, :m, 'OPEN', :now, :now)""")
                .param("id", id).param("n", request.fullName().strip()).param("e", email).param("r", request.role()).param("t", request.topic())
                .param("m", request.message().strip()).param("now", Timestamp.from(now)).update();
        return new ContactViews.Receipt(id, now);
    }

    // --- the administrators

    @Transactional(readOnly = true)
    public ContactViews.Page inbox(String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (!List.of("OPEN", "ANSWERED", "CLOSED").contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` phải là OPEN, ANSWERED hoặc CLOSED.");
        }
        long total = jdbc.sql("select count(*) from contact_requests where status = :s").param("s", status).query(Long.class).single();
        // Unanswered requests oldest first, so the one that has waited longest is on top; the others newest first.
        List<ContactViews.Summary> items = jdbc.sql("""
                select id, full_name, email, role, topic, status, message, created_at, updated_at from contact_requests where status = :s
                order by created_at %s, id limit :limit offset :offset""".formatted("OPEN".equals(status) ? "asc" : "desc"))
                .param("s", status).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new ContactViews.Summary(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("email"), rs.getString("role"),
                        rs.getString("topic"), rs.getString("status"), preview(rs.getString("message")), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
        return new ContactViews.Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public ContactViews.Detail get(UUID id) {
        record Row(UUID adminId, UUID id, String body, Instant at) {
        }
        List<Row> rows = jdbc.sql("select id, admin_id, body, created_at from contact_request_answers where request_id = :id order by created_at, id").param("id", id)
                .query((rs, n) -> new Row(rs.getObject("admin_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("body"), rs.getTimestamp("created_at").toInstant()))
                .list();
        var names = userNames.names(rows.stream().map(Row::adminId).distinct().toList());
        List<ContactViews.Answer> answers = rows.stream().map(r -> new ContactViews.Answer(r.id(), r.adminId(), names.getOrDefault(r.adminId(), "Quản trị viên"), r.body(), r.at())).toList();
        return jdbc.sql("select full_name, email, role, topic, status, message, created_at, updated_at, closed_at from contact_requests where id = :id").param("id", id)
                .query((rs, n) -> new ContactViews.Detail(id, rs.getString("full_name"), rs.getString("email"), rs.getString("role"), rs.getString("topic"),
                        rs.getString("status"), rs.getString("message"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), answers))
                .optional().orElseThrow(ContactRequestService::notFound);
    }

    /** Answers by email; the request becomes ANSWERED. A request can be answered again while it is not closed. */
    @Transactional
    public ContactViews.Detail answer(CurrentPrincipal admin, UUID id, ContactRequests.Answer request) {
        var head = jdbc.sql("select email, full_name, message from contact_requests where id = :id").param("id", id)
                .query((rs, n) -> new String[] { rs.getString("email"), rs.getString("full_name"), rs.getString("message") })
                .optional().orElseThrow(ContactRequestService::notFound);
        Timestamp now = Timestamp.from(clock.instant());
        int moved = jdbc.sql("update contact_requests set status = 'ANSWERED', updated_at = :now where id = :id and status <> 'CLOSED'").param("now", now).param("id", id).update();
        if (moved == 0) {
            throw BusinessException.conflict("CONTACT_REQUEST_CLOSED", "Yêu cầu đã đóng; không trả lời thêm được.");
        }
        String body = request.body().strip();
        jdbc.sql("insert into contact_request_answers (request_id, admin_id, body, created_at) values (:r, :a, :b, :now)")
                .param("r", id).param("a", admin.id()).param("b", body).param("now", now).update();
        events.publishEvent(new ContactRequestAnswered(id, head[0], head[1], head[2], body));
        return get(id);
    }

    @Transactional
    public ContactViews.Detail close(UUID id) {
        get(id);
        int closed = jdbc.sql("update contact_requests set status = 'CLOSED', closed_at = :now, updated_at = :now where id = :id and status <> 'CLOSED'")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (closed == 0) {
            throw BusinessException.conflict("CONTACT_REQUEST_CLOSED", "Yêu cầu đã đóng.");
        }
        return get(id);
    }

    private static String preview(String message) {
        return message.length() <= PREVIEW ? message : message.substring(0, PREVIEW - 1) + "…";
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("CONTACT_REQUEST_NOT_FOUND", "Không tìm thấy yêu cầu liên hệ.");
    }
}
