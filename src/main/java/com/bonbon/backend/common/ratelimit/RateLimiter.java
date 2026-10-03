package com.bonbon.backend.common.ratelimit;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis ({@code key} counts hits until the window expires). Fails open when Redis
 * is unavailable, the trade-off chosen in infrastructure.md: abuse limits pause, the core flow keeps working.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Counts this hit and reports whether it is still within {@code limit} for the current window. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) {
                redis.expire(key, window);
            }
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.warn("Rate limit {} skipped (Redis unavailable)", key, e);
            return true;
        }
    }

    public long current(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.warn("Rate counter {} unavailable", key, e);
            return 0;
        }
    }

    public void increment(String key, Duration window) {
        tryAcquire(key, Integer.MAX_VALUE, window);
    }

    public void reset(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not reset rate counter {}", key, e);
        }
    }
}
