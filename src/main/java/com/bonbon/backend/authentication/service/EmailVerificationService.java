package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.bonbon.backend.authentication.entity.EmailVerificationToken;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.EmailVerificationTokenRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.AuthEmails.VerificationEmailRequested;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CaptchaVerifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email verification links (24 h) and resending them (flows/authentication/verify-email.md).
 * The token row is kept until it expires, so a second click can answer "already verified".
 */
@Service
public class EmailVerificationService {

    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    public enum Outcome { VERIFIED, ALREADY_VERIFIED }

    private final EmailVerificationTokenRepository tokens;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final RateLimiter rateLimiter;
    private final CaptchaVerifier captcha;

    EmailVerificationService(EmailVerificationTokenRepository tokens, UserRepository users,
            ApplicationEventPublisher events, RateLimiter rateLimiter, CaptchaVerifier captcha) {
        this.tokens = tokens;
        this.users = users;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.captcha = captcha;
    }

    /** Creates a fresh token (older ones removed) and sends the link after commit. */
    @Transactional
    public void issue(User user) {
        tokens.deleteAllForUser(user.getId());
        String raw = SecureTokens.newRawToken();
        tokens.save(new EmailVerificationToken(user.getId(), SecureTokens.hash(raw), Instant.now().plus(TOKEN_TTL)));
        events.publishEvent(new VerificationEmailRequested(user.getEmail(), user.getName(), raw));
    }

    @Transactional
    public Outcome verify(String rawToken) {
        EmailVerificationToken token = tokens.findByTokenHash(SecureTokens.hash(rawToken))
                .filter(t -> !t.isExpired(Instant.now()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN",
                        "Liên kết không hợp lệ hoặc đã hết hạn. Bạn có thể yêu cầu gửi lại."));
        User user = users.findById(token.getUserId()).orElseThrow();
        if (user.isEmailVerified()) {
            return Outcome.ALREADY_VERIFIED;
        }
        user.markEmailVerified();
        return Outcome.VERIFIED;
    }

    /**
     * Always answers the same way (no enumeration). Sends only for an existing, local, unverified account,
     * at most once per minute and five times per day per email; a CAPTCHA is required after the first send.
     */
    @Transactional
    public void resend(String email, String captchaToken, String ip) {
        if (!rateLimiter.tryAcquire("resend_count:ip:" + ip, 5, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Bạn thao tác quá nhanh, vui lòng thử lại sau.");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String dayKey = "resend:email:day:" + normalized;
        if (rateLimiter.current(dayKey) >= 1 && !captcha.isValid(captchaToken, ip)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "Vui lòng xác nhận bạn không phải robot.");
        }
        users.findByEmail(normalized)
                .filter(u -> !u.isEmailVerified() && u.hasPassword())
                .filter(u -> rateLimiter.tryAcquire("resend:email:min:" + normalized, 1, Duration.ofSeconds(60)))
                .filter(u -> rateLimiter.tryAcquire(dayKey, 5, Duration.ofDays(1)))
                .ifPresent(this::issue);
    }
}
