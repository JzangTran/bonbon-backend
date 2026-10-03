package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.bonbon.backend.authentication.entity.PasswordResetToken;
import com.bonbon.backend.authentication.entity.PasswordResetToken.Purpose;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.PasswordResetTokenRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.AuthEmails.PasswordLinkRequested;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot / reset password (forgot-password.md) and an admin's first password (create-admin.md): a
 * single-use, hashed link; resetting kills every session through tokens_valid_after.
 */
@Service
public class PasswordRecoveryService {

    static final Duration RESET_TTL = Duration.ofHours(1);
    static final Duration INITIAL_TTL = Duration.ofHours(24);

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;

    PasswordRecoveryService(UserRepository users, PasswordResetTokenRepository resetTokens, PasswordEncoder passwords,
            TokenService tokens, RateLimiter rateLimiter, ApplicationEventPublisher events) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.passwords = passwords;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.events = events;
    }

    /** Same answer whether or not the email exists; sends only for an account that has a password. */
    @Transactional
    public void requestReset(String email, String ip) {
        if (!rateLimiter.tryAcquire("forgot_count:ip:" + ip, 5, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau.");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        users.findByEmail(normalized)
                .filter(User::hasPassword)
                .filter(u -> rateLimiter.tryAcquire("forgot:email:min:" + normalized, 1, Duration.ofSeconds(60)))
                .ifPresent(u -> issueLink(u, Purpose.RESET, RESET_TTL));
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        setPasswordFromLink(rawToken, newPassword, Purpose.RESET);
    }

    @Transactional
    public void setInitialPassword(String rawToken, String newPassword) {
        setPasswordFromLink(rawToken, newPassword, Purpose.INITIAL_PASSWORD);
    }

    /** Used by admin provisioning: an account with no password yet gets a set-your-password link. */
    @Transactional
    public void sendInitialPasswordLink(User user) {
        issueLink(user, Purpose.INITIAL_PASSWORD, INITIAL_TTL);
    }

    private void issueLink(User user, Purpose purpose, Duration ttl) {
        resetTokens.deleteAllForUser(user.getId());
        String raw = SecureTokens.newRawToken();
        resetTokens.save(new PasswordResetToken(user.getId(), purpose, SecureTokens.hash(raw), Instant.now().plus(ttl)));
        events.publishEvent(new PasswordLinkRequested(user.getEmail(), user.getName(), raw, purpose == Purpose.INITIAL_PASSWORD));
    }

    private void setPasswordFromLink(String rawToken, String newPassword, Purpose purpose) {
        PasswordResetToken token = resetTokens.findByTokenHash(SecureTokens.hash(rawToken))
                .filter(t -> t.getPurpose() == purpose && !t.isExpired(Instant.now()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN",
                        "Liên kết không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu liên kết mới."));
        User user = users.findById(token.getUserId()).orElseThrow();
        user.setPasswordHash(passwords.encode(newPassword));
        resetTokens.deleteAllForUser(user.getId());
        tokens.invalidateAllSessions(user);
    }
}
