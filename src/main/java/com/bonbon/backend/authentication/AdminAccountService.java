package com.bonbon.backend.authentication;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.LoginService;
import com.bonbon.backend.authentication.service.PasswordRecoveryService;
import com.bonbon.backend.authentication.service.SessionService;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.authentication.service.TokenService.TokenPair;
import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API for administrator accounts, used by the adminauthentication module: admins are users with
 * the ADMIN role, signed in on their own endpoint with the same token machinery and brute-force guard.
 */
@Service
public class AdminAccountService {

    private final LoginService login;
    private final TokenService tokens;
    private final SessionService sessions;
    private final PasswordRecoveryService recovery;
    private final UserRepository users;
    private final PasswordEncoder passwords;

    AdminAccountService(LoginService login, TokenService tokens, SessionService sessions,
            PasswordRecoveryService recovery, UserRepository users, PasswordEncoder passwords) {
        this.login = login;
        this.tokens = tokens;
        this.sessions = sessions;
        this.recovery = recovery;
        this.users = users;
        this.passwords = passwords;
    }

    /** Only identities holding ADMIN can pass; anyone else gets INVALID_CREDENTIALS. */
    @Transactional
    public IssuedSession signIn(String email, String password, String captchaToken, String ip) {
        User admin = login.authenticate(email, password, captchaToken, ip, EnumSet.of(Role.ADMIN));
        TokenPair pair = tokens.issue(admin, Role.ADMIN, Instant.now());
        return new IssuedSession(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds(), admin.getId(),
                admin.getEmail(), admin.getName(), Role.ADMIN);
    }

    @Transactional
    public void signOut(Jwt accessToken, String refreshToken) {
        sessions.logout(accessToken, refreshToken);
    }

    /**
     * Creates an administrator without a password (email trusted, so already verified) and emails a
     * set-your-password link. Email is unique across every role.
     */
    @Transactional
    public UUID createAdmin(String email, String name) {
        if (users.findByEmail(email.trim()).isPresent()) {
            throw emailTaken();
        }
        User admin = new User(email.trim(), null, name.trim());
        admin.addRole(Role.ADMIN);
        admin.markEmailVerified();
        try {
            admin = users.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        recovery.sendInitialPasswordLink(admin);
        return admin.getId();
    }

    /** Bootstrap: creates the first administrator once; does nothing if any administrator exists. */
    @Transactional
    public boolean seedFirstAdmin(String email, String password) {
        if (users.existsWithRole(Role.ADMIN)) {
            return false;
        }
        User admin = new User(email.trim(), passwords.encode(password), "Quản trị viên");
        admin.addRole(Role.ADMIN);
        admin.markEmailVerified();
        users.save(admin);
        return true;
    }

    private static BusinessException emailTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "Email này đã được dùng cho một tài khoản khác.");
    }
}
