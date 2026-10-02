package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Short-lived, single-use proof that a dual-role identity just passed the password check, so the role
 * choice cannot be replayed against another login attempt. Stored hashed in Redis for 5 minutes.
 */
@Component
class RoleSelectionStore {

    private static final Duration TTL = Duration.ofMinutes(5);

    record PendingSelection(UUID userId, Instant authTime) {
    }

    private final StringRedisTemplate redis;

    RoleSelectionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    String create(UUID userId, Instant authTime) {
        String raw = SecureTokens.newRawToken();
        redis.opsForValue().set(key(raw), userId + "|" + authTime.getEpochSecond(), TTL);
        return raw;
    }

    Optional<PendingSelection> consume(String rawToken) {
        String value = redis.opsForValue().getAndDelete(key(rawToken));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|");
        return Optional.of(new PendingSelection(UUID.fromString(parts[0]), Instant.ofEpochSecond(Long.parseLong(parts[1]))));
    }

    private static String key(String rawToken) {
        return "role_select:" + SecureTokens.hash(rawToken);
    }
}
