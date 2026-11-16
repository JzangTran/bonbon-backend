package com.bonbon.backend.identity.sellerteam.service;

import java.security.SecureRandom;
import java.time.Duration;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.identity.authentication.service.SecureTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Six-digit codes mailed to a main account's address (register, link a shop, recover the password). Stored hashed in Redis for
 * 5 minutes, 5 tries, one mail a minute and 10 a day per address.
 */
@Component
class TeamCodes {

    private static final Logger log = LoggerFactory.getLogger(TeamCodes.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration TTL = Duration.ofMinutes(5);
    static final int MAX_TRIES = 5;

    private final StringRedisTemplate redis;
    private final RateLimiter limiter;
    private final EmailSender mail;

    TeamCodes(StringRedisTemplate redis, RateLimiter limiter, EmailSender mail) {
        this.redis = redis;
        this.limiter = limiter;
        this.mail = mail;
    }

    /** {@code purpose} is register, link or recover; {@code subject} is what the code is bound to (an address or an account id). */
    void send(String purpose, String subject, String email, String what) {
        String to = email.strip().toLowerCase();
        if (!limiter.tryAcquire("team-code-cooldown:" + purpose + ":" + subject, 1, Duration.ofSeconds(60))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CODE_SENT_RECENTLY", "Mã vừa được gửi. Hãy chờ một phút rồi gửi lại.");
        }
        if (!limiter.tryAcquire("team-code-daily:" + to, 10, Duration.ofDays(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "CODE_DAILY_LIMIT", "Đã gửi quá nhiều mã tới email này hôm nay.");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(key(purpose, subject), SecureTokens.hash(code), TTL);
        redis.delete(triesKey(purpose, subject));
        mail.send(new EmailSender.EmailMessage(to, "Mã xác nhận bonbon: " + code,
                "Mã xác nhận để " + what + " là: " + code + "\n\nMã có hiệu lực 5 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này."));
        log.debug("Team code sent for {}", purpose);
    }

    /** Consumes the code when right; a wrong code counts a try, five wrong tries void it. */
    void verify(String purpose, String subject, String code) {
        String stored = redis.opsForValue().get(key(purpose, subject));
        if (stored == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "Mã đã hết hạn. Hãy gửi lại mã mới.");
        }
        if (!stored.equals(SecureTokens.hash(code == null ? "" : code.strip()))) {
            Long tries = redis.opsForValue().increment(triesKey(purpose, subject));
            redis.expire(triesKey(purpose, subject), TTL);
            if (tries != null && tries >= MAX_TRIES) {
                redis.delete(key(purpose, subject));
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_EXPIRED", "Nhập sai quá nhiều lần. Hãy gửi lại mã mới.");
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "Mã xác nhận không đúng.");
        }
        redis.delete(key(purpose, subject));
        redis.delete(triesKey(purpose, subject));
    }

    private static String key(String purpose, String subject) {
        return "code:team-" + purpose + ":" + subject;
    }

    private static String triesKey(String purpose, String subject) {
        return "code:team-" + purpose + ":" + subject + ":tries";
    }
}
