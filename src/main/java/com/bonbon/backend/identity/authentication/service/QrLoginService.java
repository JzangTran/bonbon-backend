package com.bonbon.backend.identity.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Inspect;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.Started;
import com.bonbon.backend.identity.authentication.dto.QrLoginDtos.State;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing the seller web app in by scanning a QR code with the already signed-in phone (login-qr-code.md).
 * <p>
 * The whole handshake lives in Redis for {@link #TTL}. Two secrets are made at the start: the session id, which goes into the QR
 * code and so can be seen by anyone who sees the screen, and the poll token, which only the browser that asked keeps. Reading the
 * state and taking the tokens both need the poll token, so a photo of the QR code cannot be used to steal the sign-in. The phone
 * never signs in by scanning alone: it shows the browser and IP and waits for the seller to confirm. Exchanging a confirmed
 * session deletes it, so the tokens are handed out once.
 */
@Service
public class QrLoginService {

    static final Duration TTL = Duration.ofMinutes(3);
    static final int STARTS_PER_IP = 10;
    static final Duration START_WINDOW = Duration.ofMinutes(1);
    static final String QR_PREFIX = "bonbon://qr-login?s=";

    private static final String F_STATE = "state";
    private static final String F_POLL = "poll";
    private static final String F_UA = "ua";
    private static final String F_IP = "ip";
    private static final String F_AT = "at";
    private static final String F_USER = "user";

    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final UserRepository users;
    private final LoginService login;

    QrLoginService(StringRedisTemplate redis, RateLimiter rateLimiter, UserRepository users, LoginService login) {
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.users = users;
        this.login = login;
    }

    /** Web: a new code. Limited per IP so the endpoint cannot be used to fill Redis. */
    public Started start(String userAgent, String ip) {
        if (!rateLimiter.tryAcquire("rl:qr_login_start:" + ip, STARTS_PER_IP, START_WINDOW)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Bạn tạo mã quá nhanh. Vui lòng thử lại sau một phút.");
        }
        String sessionId = SecureTokens.newRawToken();
        String pollToken = SecureTokens.newRawToken();
        Map<String, String> fields = new HashMap<>();
        fields.put(F_STATE, State.PENDING.name());
        fields.put(F_POLL, SecureTokens.hash(pollToken));
        fields.put(F_UA, truncate(userAgent == null ? "" : userAgent, 400));
        fields.put(F_IP, ip == null ? "" : ip);
        fields.put(F_AT, String.valueOf(Instant.now().getEpochSecond()));
        String key = key(sessionId);
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, TTL);
        return new Started(sessionId, pollToken, QR_PREFIX + sessionId, TTL.toSeconds());
    }

    /** Web: where the handshake is. A wrong poll token looks the same as an expired session. */
    public State status(String sessionId, String pollToken) {
        return owned(sessionId, pollToken).map(s -> State.valueOf(s.get(F_STATE))).orElse(State.EXPIRED);
    }

    /** Phone: the seller scanned the code. Binds the session to this identity and shows what is asking to sign in. */
    public Inspect scan(UUID userId, String sessionId) {
        User user = seller(userId);
        String key = key(sessionId);
        Map<String, String> s = read(key).orElseThrow(QrLoginService::expired);
        State state = State.valueOf(s.get(F_STATE));
        String boundTo = s.get(F_USER);
        if (state == State.SCANNED && !user.getId().toString().equals(boundTo)) {
            throw new BusinessException(HttpStatus.CONFLICT, "QR_ALREADY_SCANNED", "Mã này đã được quét bằng tài khoản khác.");
        }
        if (state != State.PENDING && state != State.SCANNED) {
            throw expired();
        }
        redis.opsForHash().put(key, F_STATE, State.SCANNED.name());
        redis.opsForHash().put(key, F_USER, user.getId().toString());
        Instant at = Instant.ofEpochSecond(Long.parseLong(s.get(F_AT)));
        return new Inspect(describe(s.get(F_UA)), s.get(F_IP), at, at.plus(TTL));
    }

    /** Phone: the seller read the confirmation screen and accepted. Only the identity that scanned may answer. */
    public void confirm(UUID userId, String sessionId) {
        answer(userId, sessionId, State.CONFIRMED);
    }

    /** Phone: the seller did not recognise the browser. */
    public void reject(UUID userId, String sessionId) {
        answer(userId, sessionId, State.REJECTED);
    }

    /** Web: trade a confirmed session for the seller's tokens, once. */
    @Transactional
    public LoginResponse exchange(String sessionId, String pollToken) {
        Map<String, String> s = owned(sessionId, pollToken).orElseThrow(QrLoginService::expired);
        if (State.valueOf(s.get(F_STATE)) != State.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT, "QR_NOT_CONFIRMED", "Phiên chưa được xác nhận trên điện thoại.");
        }
        // Whoever deletes the key wins; a second exchange (a replay, or a double call) finds nothing.
        if (!Boolean.TRUE.equals(redis.delete(key(sessionId)))) {
            throw expired();
        }
        User user = seller(UUID.fromString(s.get(F_USER)));
        return login.complete(user, Role.SELLER, Instant.now());
    }

    private void answer(UUID userId, String sessionId, State answer) {
        String key = key(sessionId);
        Map<String, String> s = read(key).orElseThrow(QrLoginService::expired);
        if (State.valueOf(s.get(F_STATE)) != State.SCANNED) {
            throw expired();
        }
        if (!userId.toString().equals(s.get(F_USER))) {
            throw new BusinessException(HttpStatus.CONFLICT, "QR_ALREADY_SCANNED", "Mã này đã được quét bằng tài khoản khác.");
        }
        if (answer == State.CONFIRMED) {
            seller(userId); // the role could have been removed between the scan and the answer
        }
        redis.opsForHash().put(key, F_STATE, answer.name());
    }

    private User seller(UUID userId) {
        User user = users.findById(userId).orElseThrow(() ->
                new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập."));
        if (!user.hasRole(Role.SELLER)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NO_SELLER_ACCESS",
                    "Tài khoản này không có quyền bán hàng nên không đăng nhập được trang quản lý quán.");
        }
        return user;
    }

    private Optional<Map<String, String>> owned(String sessionId, String pollToken) {
        return read(key(sessionId)).filter(s -> SecureTokens.hash(pollToken).equals(s.get(F_POLL)));
    }

    private Optional<Map<String, String>> read(String key) {
        Map<Object, Object> raw = redis.opsForHash().entries(key);
        if (raw.isEmpty() || raw.get(F_STATE) == null) {
            return Optional.empty();
        }
        Map<String, String> s = new HashMap<>();
        raw.forEach((k, v) -> s.put(String.valueOf(k), String.valueOf(v)));
        return Optional.of(s);
    }

    private static String key(String sessionId) {
        return "qr_login:" + SecureTokens.hash(sessionId);
    }

    private static BusinessException expired() {
        return new BusinessException(HttpStatus.GONE, "QR_EXPIRED", "Mã đã hết hạn hoặc đã được dùng. Hãy tạo mã mới trên trang đăng nhập.");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** ‘Chrome trên Windows’ from a User-Agent; enough for a person to recognise their own browser. */
    public static String describe(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Trình duyệt không rõ";
        }
        String ua = userAgent.toLowerCase();
        String browser = ua.contains("edg/") ? "Edge"
                : ua.contains("opr/") || ua.contains("opera") ? "Opera"
                : ua.contains("coc_coc") || ua.contains("coccoc") ? "Cốc Cốc"
                : ua.contains("firefox/") ? "Firefox"
                : ua.contains("chrome/") || ua.contains("crios/") ? "Chrome"
                : ua.contains("safari/") ? "Safari"
                : "Trình duyệt khác";
        String os = ua.contains("windows") ? "Windows"
                : ua.contains("android") ? "Android"
                : ua.contains("iphone") || ua.contains("ipad") ? "iOS"
                : ua.contains("mac os") || ua.contains("macintosh") ? "macOS"
                : ua.contains("linux") ? "Linux"
                : "hệ điều hành không rõ";
        return browser + " trên " + os;
    }
}
