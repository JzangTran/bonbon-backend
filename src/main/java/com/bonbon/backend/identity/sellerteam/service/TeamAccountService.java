package com.bonbon.backend.identity.sellerteam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.validation.PasswordPolicy;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import com.bonbon.backend.identity.authentication.service.LoginAttemptGuard;
import com.bonbon.backend.identity.sellerteam.dto.TeamDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Main accounts: registered from inside a shop account (re-entering its password, confirming the address with a mailed code), signed in
 * as {@code <username>:main}, recovered with a mailed code. Members sign in here too, as {@code <username>:<code>}. Unknown login, unknown
 * member and wrong password all answer the same; a member that is disabled is told so only after the right password.
 */
@Service
public class TeamAccountService {

    /** A shop account must have proved who it is this recently to skip re-typing its password (link-shop.md). */
    static final Duration RECENT_AUTH = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final TeamCodes codes;
    private final TeamSessions sessions;
    private final LoginAttemptGuard guard;
    private final CaptchaVerifier captcha;
    private final com.bonbon.backend.identity.sellerteam.TeamShops shops;
    private final Clock clock = Clock.systemUTC();
    private final String dummyHash;

    TeamAccountService(JdbcClient jdbc, PasswordEncoder passwords, TeamCodes codes, TeamSessions sessions, LoginAttemptGuard guard,
            CaptchaVerifier captcha, com.bonbon.backend.identity.sellerteam.TeamShops shops) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.codes = codes;
        this.sessions = sessions;
        this.guard = guard;
        this.captcha = captcha;
        this.shops = shops;
        this.dummyHash = passwords.encode("not-a-real-password-" + UUID.randomUUID());
    }

    // --- registering, from a shop account

    @Transactional(readOnly = true)
    public void sendRegisterCode(CurrentPrincipal shopAccount, String email) {
        ensureNoMainAccount(shopAccount.id());
        if (emailTaken(email)) {
            throw BusinessException.conflict("EMAIL_TAKEN", "Email này đã dùng cho một tài khoản chính khác.");
        }
        codes.send("register", email.strip().toLowerCase(), email, "tạo tài khoản chính bonbon");
    }

    @Transactional
    public TeamDtos.MainAccount register(CurrentPrincipal shopAccount, Instant authTime, TeamDtos.Register r) {
        stepUp(shopAccount.id(), authTime, r.currentPassword());
        ensureNoMainAccount(shopAccount.id());
        if (!r.password().equals(r.confirmPassword())) {
            throw fieldError("confirmPassword", "Mật khẩu nhập lại không khớp.");
        }
        PasswordPolicy.problem(r.password(), r.email(), r.accountName()).ifPresent(p -> {
            throw fieldError("password", p);
        });
        if (emailTaken(r.email())) {
            throw BusinessException.conflict("EMAIL_TAKEN", "Email này đã dùng cho một tài khoản chính khác.");
        }
        if (jdbc.sql("select count(*) from identity.main_accounts where username = :u").param("u", r.username()).query(Long.class).single() > 0) {
            throw BusinessException.conflict("USERNAME_TAKEN", "Tên đăng nhập này đã có người dùng.");
        }
        codes.verify("register", r.email().strip().toLowerCase(), r.code());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into identity.main_accounts (id, username, account_name, region, email, phone, nickname, password_hash, created_by_user_id)
                    values (:id, :u, :n, :r, :e, :p, :nick, :h, :by)""")
                    .param("id", id).param("u", r.username()).param("n", r.accountName().strip()).param("r", blankToNull(r.region()))
                    .param("e", r.email().strip().toLowerCase()).param("p", blankToNull(r.phone())).param("nick", blankToNull(r.nickname()))
                    .param("h", passwords.encode(r.password())).param("by", shopAccount.id()).update();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("USERNAME_TAKEN", "Tên đăng nhập hoặc email đã có người dùng.");
        }
        UUID shopId = shops.ofOwner(shopAccount.id()).filter(s -> "APPROVED".equals(s.status()) || "SUSPENDED".equals(s.status()))
                .map(com.bonbon.backend.identity.sellerteam.TeamShops.TeamShop::vendorId).orElse(null);
        // The shop that registers it is linked at once: the same person just proved both sides (password and mailed code).
        if (shopId != null) {
            jdbc.sql("""
                    insert into identity.main_account_shop_links (main_account_id, vendor_id, owner_user_id, status, expires_at, decided_at)
                    select :m, :v, :o, 'AUTHORIZED', now(), now() where not exists (
                        select 1 from identity.main_account_shop_links where vendor_id = :v and status in ('PENDING_AUTHORIZATION', 'AUTHORIZED'))""")
                    .param("m", id).param("v", shopId).param("o", shopAccount.id()).update();
        }
        return view(id);
    }

    /** The shop account's own main account, or 404. */
    @Transactional(readOnly = true)
    public TeamDtos.MainAccount mineAsShop(UUID shopAccountId) {
        return jdbc.sql("select id from identity.main_accounts where created_by_user_id = :u").param("u", shopAccountId).query(UUID.class)
                .optional().map(this::view)
                .orElseThrow(() -> BusinessException.notFound("MAIN_ACCOUNT_NOT_FOUND", "Bạn chưa có tài khoản chính."));
    }

    // --- signing in

    @Transactional
    public TokenResponse login(TeamDtos.Login request, String ip) {
        String login = request.login().strip().toLowerCase();
        String key = "team:" + login;
        if (guard.requiresCaptcha(key, ip) && !captcha.isValid(request.captchaToken(), ip)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "Vui lòng xác nhận bạn không phải robot.");
        }
        int colon = login.indexOf(':');
        String username = colon < 0 ? login : login.substring(0, colon);
        String code = colon < 0 ? "" : login.substring(colon + 1);
        record Found(UUID id, String hash, String status) {
        }
        Found found;
        String kind;
        if (code.equals("main")) {
            kind = "MAIN_ACCOUNT";
            found = jdbc.sql("select id, password_hash, 'ACTIVE' from identity.main_accounts where username = :u").param("u", username)
                    .query((rs, n) -> new Found(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional().orElse(null);
        } else {
            kind = "MEMBER";
            found = code.isEmpty() ? null : jdbc.sql("""
                    select m.id, m.password_hash, m.status from identity.team_members m join identity.main_accounts a on a.id = m.main_account_id
                    where a.username = :u and m.login_code = :c""").param("u", username).param("c", code)
                    .query((rs, n) -> new Found(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional().orElse(null);
        }
        // compare even when not found, so timing says nothing about which logins exist
        boolean ok = passwords.matches(request.password(), found == null ? dummyHash : found.hash());
        if (found == null || !ok) {
            guard.recordFailure(key, ip);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Tên đăng nhập hoặc mật khẩu không đúng.");
        }
        if (!"ACTIVE".equals(found.status())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MEMBER_DISABLED", "Tài khoản đã bị tạm khoá. Hãy liên hệ chủ tài khoản chính.");
        }
        guard.recordSuccess(key, ip);
        return sessions.issue(kind, found.id(), null, clock.instant());
    }

    /** Opens another shop (or the console) in a new token pair; the shop must still be linked or assigned. */
    @Transactional
    public TokenResponse switchShop(CurrentPrincipal caller, Instant authTime, UUID vendorId) {
        requireTeam(caller);
        String kind = caller.actorType().name();
        if (vendorId == null && !"MAIN_ACCOUNT".equals(kind)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SHOP_NOT_ASSIGNED", "Thành viên luôn làm việc trong một cửa hàng.");
        }
        return sessions.issue(kind, caller.teamId(), vendorId, authTime);
    }

    @Transactional
    public TokenResponse current(CurrentPrincipal caller, Instant authTime) {
        requireTeam(caller);
        return sessions.issue(caller.actorType().name(), caller.teamId(), caller.shopId(), authTime);
    }

    // --- recovering a main account

    /** Always accepted: whether the address backs an account is never said. */
    @Transactional(readOnly = true)
    public void sendRecoverCode(String email) {
        String to = email.strip().toLowerCase();
        jdbc.sql("select id from identity.main_accounts where lower(email) = :e").param("e", to).query(UUID.class).optional()
                .ifPresent(id -> codes.send("recover", to, to, "đặt lại mật khẩu tài khoản chính"));
    }

    @Transactional
    public void recover(TeamDtos.Recover r) {
        String to = r.email().strip().toLowerCase();
        codes.verify("recover", to, r.code());
        UUID id = jdbc.sql("select id from identity.main_accounts where lower(email) = :e").param("e", to).query(UUID.class).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "Mã xác nhận không đúng."));
        PasswordPolicy.problem(r.newPassword(), to, null).ifPresent(p -> {
            throw fieldError("newPassword", p);
        });
        jdbc.sql("update identity.main_accounts set password_hash = :h where id = :id").param("h", passwords.encode(r.newPassword())).param("id", id).update();
        sessions.invalidate("MAIN_ACCOUNT", id);
    }

    // --- helpers shared with the other team services

    TeamDtos.MainAccount view(UUID id) {
        return jdbc.sql("select * from identity.main_accounts where id = :id").param("id", id)
                .query((rs, n) -> new TeamDtos.MainAccount(id, rs.getString("username") + ":main", rs.getString("account_name"), rs.getString("region"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("nickname"), rs.getTimestamp("created_at").toInstant(), List.of()))
                .single();
    }

    /** Re-typing the shop account's password, unless it really signed in within ten minutes. Wrong passwords count as failed logins. */
    void stepUp(UUID userId, Instant authTime, String password) {
        boolean recent = authTime != null && authTime.isAfter(clock.instant().minus(RECENT_AUTH));
        if (password == null || password.isBlank()) {
            if (recent) {
                return;
            }
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Hãy nhập lại mật khẩu tài khoản cửa hàng để tiếp tục.");
        }
        Map.Entry<String, String> row = jdbc.sql("select email, password_hash from identity.users where id = :u").param("u", userId)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2) == null ? "" : rs.getString(2))).single();
        if (row.getValue().isEmpty() || !passwords.matches(password, row.getValue())) {
            guard.recordFailure(row.getKey(), "step-up");
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Mật khẩu không đúng.");
        }
    }

    static void requireTeam(CurrentPrincipal caller) {
        if (!caller.isTeam()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Chỉ dành cho tài khoản chính hoặc thành viên.");
        }
    }

    static BusinessException fieldError(String field, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message)
                .withProperty("errors", List.of(Map.of("field", field, "message", message)));
    }

    private void ensureNoMainAccount(UUID shopAccountId) {
        if (jdbc.sql("select count(*) from identity.main_accounts where created_by_user_id = :u").param("u", shopAccountId).query(Long.class).single() > 0) {
            throw BusinessException.conflict("ALREADY_HAS_MAIN_ACCOUNT", "Bạn đã có tài khoản chính. Hãy đăng nhập tài khoản chính để liên kết thêm cửa hàng.");
        }
    }

    private boolean emailTaken(String email) {
        return jdbc.sql("select count(*) from identity.main_accounts where lower(email) = :e").param("e", email.strip().toLowerCase())
                .query(Long.class).single() > 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
