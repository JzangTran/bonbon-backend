package com.bonbon.backend.identity.developer.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import com.bonbon.backend.identity.authentication.dto.UserSummary;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.LoginService;
import com.bonbon.backend.identity.authentication.service.PasswordRecoveryService;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.bonbon.backend.identity.developer.dto.DeveloperDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Access to the API reference (developer portal). Nobody signs up as a developer: a person sends a request to the team, an
 * administrator approves it, and only then does the account hold the DEVELOPER role, whose one permission reads the API reference.
 * A new address gets an account with a set-your-password link; an existing customer or seller account gets the role added and signs
 * in with its own password. Rejecting needs a reason the person reads; revoking takes the role away and ends every session.
 */
@Service
public class DeveloperAccessService {

    static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordRecoveryService recovery;
    private final TokenService tokens;
    private final LoginService login;
    private final RateLimiter limiter;
    private final CaptchaVerifier captcha;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final EmailSender mail;
    private final String portalUrl;

    DeveloperAccessService(JdbcClient jdbc, UserRepository users, PasswordRecoveryService recovery, TokenService tokens, LoginService login,
            RateLimiter limiter, CaptchaVerifier captcha, org.springframework.context.ApplicationEventPublisher events, EmailSender mail,
            @Value("${bonbon.app.developers-url:${bonbon.app.web-url:http://localhost:5173}/developers}") String portalUrl) {
        this.jdbc = jdbc;
        this.users = users;
        this.recovery = recovery;
        this.tokens = tokens;
        this.login = login;
        this.limiter = limiter;
        this.captcha = captcha;
        this.events = events;
        this.mail = mail;
        this.portalUrl = portalUrl;
    }

    /** What a decision sends; mailed only after the transaction commits. */
    record Decided(String email, String name, boolean approved, boolean newAccount, String reason) {
    }

    // --- the person asking

    @Transactional
    public DeveloperDtos.Received request(DeveloperDtos.Create r, String ip) {
        if (!limiter.tryAcquire("developer-request:" + ip, 3, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "Bạn đã gửi quá nhiều yêu cầu hôm nay.");
        }
        if (!captcha.isValid(r.captchaToken(), ip)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "Vui lòng xác nhận bạn không phải robot.");
        }
        String email = r.email().strip().toLowerCase();
        if (users.findByEmail(email).filter(u -> u.hasRole(Role.DEVELOPER)).isPresent()) {
            throw BusinessException.conflict("ALREADY_DEVELOPER", "Email này đã được cấp quyền. Hãy đăng nhập trang dành cho lập trình viên.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into identity.developer_access_requests (id, name, email, company, website, purpose)
                    values (:id, :n, :e, :c, :w, :p)""")
                    .param("id", id).param("n", r.name().strip()).param("e", email).param("c", blank(r.company()))
                    .param("w", blank(r.website())).param("p", r.purpose().strip()).update();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("REQUEST_PENDING", "Email này đã có một yêu cầu đang chờ duyệt.");
        }
        return new DeveloperDtos.Received(id, "PENDING");
    }

    /** Only the DEVELOPER role signs in here; an account without it gets the same answer as a wrong password. */
    @Transactional
    public LoginResponse login(DeveloperDtos.Login r, String ip) {
        User user = login.authenticate(r.email(), r.password(), r.captchaToken(), ip, java.util.EnumSet.of(Role.DEVELOPER));
        return LoginResponse.signedIn(TokenResponse.of(tokens.issue(user, Role.DEVELOPER, Instant.now())), UserSummary.of(user, Role.DEVELOPER));
    }

    // --- administrators

    @Transactional(readOnly = true)
    public DeveloperDtos.Page queue(String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String s = status == null || status.isBlank() ? "PENDING" : status;
        if (!s.matches("PENDING|APPROVED|REJECTED|REVOKED|ALL")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Trạng thái không hợp lệ.");
        }
        String where = s.equals("ALL") ? "" : " where r.status = '" + s + "'";
        long total = jdbc.sql("select count(*) from identity.developer_access_requests r" + where).query(Long.class).single();
        List<DeveloperDtos.AdminView> items = jdbc.sql("""
                select r.*, r.status = 'PENDING' and exists (select 1 from identity.users u where lower(u.email) = r.email) as existing
                from identity.developer_access_requests r""" + where + " order by r.created_at desc limit :limit offset :offset")
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new DeveloperDtos.AdminView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                        rs.getString("company"), rs.getString("website"), rs.getString("purpose"), rs.getString("status"), rs.getString("reason"),
                        rs.getObject("user_id", UUID.class), rs.getBoolean("existing"), rs.getTimestamp("created_at").toInstant(),
                        ts(rs.getTimestamp("decided_at"))))
                .list();
        return new DeveloperDtos.Page(items, page, size, total);
    }

    @Transactional
    public void approve(UUID adminId, UUID id) {
        Row r = pending(id);
        User user = users.findByEmail(r.email()).orElse(null);
        boolean created = user == null;
        if (user != null && user.hasRole(Role.ADMIN)) {
            throw BusinessException.conflict("ADMIN_ACCOUNT", "Tài khoản quản trị không nhận thêm vai trò khác. Hãy dùng email khác.");
        }
        if (created) {
            user = new User(r.email(), null, r.name());
            user.markEmailVerified(); // the set-password link proves the address
        }
        user.addRole(Role.DEVELOPER);
        user = users.saveAndFlush(user);
        if (created) {
            recovery.sendInitialPasswordLink(user);
        }
        close(id, "APPROVED", adminId, null, user.getId());
        events.publishEvent(new Decided(r.email(), r.name(), true, created, null));
    }

    @Transactional
    public void reject(UUID adminId, UUID id, String reason) {
        Row r = pending(id);
        close(id, "REJECTED", adminId, reason.strip(), null);
        events.publishEvent(new Decided(r.email(), r.name(), false, false, reason.strip()));
    }

    /** Takes the role away; every session of that account ends, its customer or seller role stays. */
    @Transactional
    public void revoke(UUID adminId, UUID id, String reason) {
        Row r = find(id);
        if (!"APPROVED".equals(r.status())) {
            throw BusinessException.conflict("NOT_APPROVED", "Chỉ thu hồi được yêu cầu đã duyệt.");
        }
        users.findById(r.userId()).ifPresent(u -> {
            u.removeRole(Role.DEVELOPER);
            users.saveAndFlush(u);
            tokens.invalidateAllSessions(u);
        });
        close(id, "REVOKED", adminId, reason.strip(), r.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onDecided(Decided d) {
        String body;
        if (d.approved()) {
            body = "Chào " + d.name() + ",\n\nYêu cầu truy cập tài liệu API bonbon của bạn đã được duyệt.\n"
                    + (d.newAccount() ? "Hãy mở thư \"Đặt mật khẩu tài khoản bonbon\" để đặt mật khẩu, rồi đăng nhập tại " : "Đăng nhập bằng email và mật khẩu bonbon hiện có tại ")
                    + portalUrl + "\n";
        } else {
            body = "Chào " + d.name() + ",\n\nYêu cầu truy cập tài liệu API bonbon của bạn chưa được duyệt.\nLý do: " + d.reason()
                    + "\n\nBạn có thể gửi lại yêu cầu với thông tin đầy đủ hơn.";
        }
        mail.send(new EmailSender.EmailMessage(d.email(), d.approved() ? "Đã duyệt truy cập tài liệu API bonbon" : "Yêu cầu truy cập API bonbon", body));
    }

    // --- internals

    private record Row(UUID id, String name, String email, String status, UUID userId) {
    }

    private Row find(UUID id) {
        return jdbc.sql("select id, name, email, status, user_id from identity.developer_access_requests where id = :id for update").param("id", id)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class)))
                .optional().orElseThrow(() -> BusinessException.notFound("ACCESS_REQUEST_NOT_FOUND", "Không tìm thấy yêu cầu."));
    }

    private Row pending(UUID id) {
        Row r = find(id);
        if (!"PENDING".equals(r.status())) {
            throw BusinessException.conflict("ACCESS_REQUEST_DECIDED", "Yêu cầu đã được xử lý.");
        }
        return r;
    }

    private void close(UUID id, String status, UUID adminId, String reason, UUID userId) {
        jdbc.sql("""
                update identity.developer_access_requests set status = :s, reason = :r, decided_by = :a, decided_at = now(),
                       user_id = coalesce(cast(:u as uuid), user_id) where id = :id""")
                .param("s", status).param("r", reason).param("a", adminId).param("u", userId).param("id", id).update();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
