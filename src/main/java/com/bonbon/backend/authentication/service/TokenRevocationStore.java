package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed state for revoking access tokens: a jti blacklist (logout) and a cached copy of each
 * user's tokens_valid_after. Redis being down fails open by design (infrastructure.md): the blacklist
 * check is skipped and tokens_valid_after falls back to the database.
 */
@Component
class TokenRevocationStore {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationStore.class);
    private static final Duration VALID_AFTER_CACHE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    TokenRevocationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    void blacklist(String jti, Duration remainingLifetime) {
        if (remainingLifetime.isNegative() || remainingLifetime.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(blacklistKey(jti), "1", remainingLifetime);
        } catch (RuntimeException e) {
            log.warn("Could not blacklist token {} (Redis unavailable)", jti, e);
        }
    }

    boolean isBlacklisted(String jti) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(blacklistKey(jti)));
        } catch (RuntimeException e) {
            log.warn("Blacklist check skipped (Redis unavailable)", e);
            return false;
        }
    }

    Optional<Instant> cachedValidAfter(UUID userId) {
        try {
            String value = redis.opsForValue().get(validAfterKey(userId));
            return Optional.ofNullable(value).map(v -> Instant.ofEpochMilli(Long.parseLong(v)));
        } catch (RuntimeException e) {
            log.warn("tokens_valid_after cache unavailable", e);
            return Optional.empty();
        }
    }

    void cacheValidAfter(UUID userId, Instant validAfter) {
        try {
            redis.opsForValue().set(validAfterKey(userId), Long.toString(validAfter.toEpochMilli()), VALID_AFTER_CACHE_TTL);
        } catch (RuntimeException e) {
            log.warn("Could not cache tokens_valid_after for {}", userId, e);
        }
    }

    private static String blacklistKey(String jti) {
        return "blacklist:" + jti;
    }

    private static String validAfterKey(UUID userId) {
        return "tva:" + userId;
    }
}
