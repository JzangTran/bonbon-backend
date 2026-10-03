package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ending sessions: this device (logout.md) or every device after a password re-check (logout-all-devices.md). */
@Service
public class SessionService {

    /** Same step-up window as link-shop: an OAuth-only account must have signed in within this time. */
    private static final Duration STEP_UP_WINDOW = Duration.ofMinutes(10);

    private final TokenService tokens;
    private final UserRepository users;
    private final PasswordEncoder passwords;

    SessionService(TokenService tokens, UserRepository users, PasswordEncoder passwords) {
        this.tokens = tokens;
        this.users = users;
        this.passwords = passwords;
    }

    @Transactional
    public void logout(Jwt accessToken, String refreshToken) {
        tokens.revokeSession(accessToken, refreshToken);
    }

    @Transactional
    public void logoutEverywhere(Jwt accessToken, String password) {
        User user = users.findById(UUID.fromString(accessToken.getSubject())).orElseThrow();
        if (user.hasPassword()) {
            if (password == null || !passwords.matches(password, user.getPasswordHash())) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Mật khẩu không đúng.");
            }
        } else if (!recentlyAuthenticated(accessToken)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTHENTICATION_REQUIRED",
                    "Vui lòng đăng nhập lại để xác nhận.");
        }
        tokens.invalidateAllSessions(user);
    }

    private static boolean recentlyAuthenticated(Jwt jwt) {
        Object claim = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        if (!(claim instanceof Number seconds)) {
            return false;
        }
        return Instant.ofEpochSecond(seconds.longValue()).isAfter(Instant.now().minus(STEP_UP_WINDOW));
    }
}
