package com.bonbon.backend.common;

import java.time.Duration;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SystemSettingsServiceTests {

    @Autowired
    SystemSettingsService settings;

    @Autowired
    JdbcClient jdbc;

    @Test
    void flywayCreatedTheTable() {
        Integer count = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single();
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingKeyFallsBackToDefault() {
        assertThat(settings.getInt("test.never_set", 15)).isEqualTo(15);
        assertThat(settings.getDuration("test.never_set_duration", Duration.ofMinutes(10)))
                .isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void writeIsVisibleOnNextReadDespiteCache() {
        UUID admin = UUID.randomUUID();
        assertThat(settings.getInt("test.order_timeout_minutes", 15)).isEqualTo(15);

        settings.set("test.order_timeout_minutes", "20", ActorType.ADMIN, admin);
        assertThat(settings.getInt("test.order_timeout_minutes", 15)).isEqualTo(20);

        settings.set("test.order_timeout_minutes", "25", ActorType.ADMIN, admin);
        assertThat(settings.getInt("test.order_timeout_minutes", 15)).isEqualTo(25);
    }

    @Test
    void keyFormatIsEnforcedByTheDatabase() {
        assertThatThrownBy(() -> settings.set("NoDots", "x", ActorType.SYSTEM, null))
                .hasMessageContaining("system_settings_key_format");
    }
}
