package com.bonbon.backend.common.settings;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bonbon.backend.common.persistence.ActorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes {@code system_settings}. Values change rarely, so reads are served from an in-memory
 * cache that is cleared on every write by this instance. Callers always pass the default that applies when
 * a key was never set, so a missing row is never an error.
 */
@Service
public class SystemSettingsService {

    private final JdbcClient jdbc;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public SystemSettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String getString(String key, String defaultValue) {
        return find(key).orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return find(key).map(Integer::parseInt).orElse(defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        return find(key).map(Long::parseLong).orElse(defaultValue);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return find(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /** Values are ISO-8601 durations, e.g. {@code PT15M}. */
    public Duration getDuration(String key, Duration defaultValue) {
        return find(key).map(Duration::parse).orElse(defaultValue);
    }

    @Transactional
    public void set(String key, String value, ActorType actorType, UUID actorId) {
        jdbc.sql("""
                INSERT INTO system_settings (key, value, updated_by_type, updated_by_id, updated_at)
                VALUES (:key, :value, :type, :id, now())
                ON CONFLICT (key) DO UPDATE
                SET value = EXCLUDED.value, updated_by_type = EXCLUDED.updated_by_type,
                    updated_by_id = EXCLUDED.updated_by_id, updated_at = EXCLUDED.updated_at
                """)
                .param("key", key)
                .param("value", value)
                .param("type", actorType.name())
                .param("id", actorId)
                .update();
        cache.remove(key);
    }

    private Optional<String> find(String key) {
        return cache.computeIfAbsent(key, k -> jdbc.sql("SELECT value FROM system_settings WHERE key = :key")
                .param("key", k)
                .query(String.class)
                .optional());
    }
}
