package com.bonbon.backend;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL, Redis and S3-compatible storage for every {@code @SpringBootTest}, same images as
 * deploy/compose.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    RedisContainer redis() {
        return new RedisContainer(DockerImageName.parse("redis:7.4-alpine"));
    }

    @Bean
    GenericContainer<?> s3() {
        return new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
                .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "bonbon-private,bonbon-public")
                .withExposedPorts(9090);
    }

    @Bean
    DynamicPropertyRegistrar s3Properties(GenericContainer<?> s3) {
        return registry -> {
            String endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(9090);
            registry.add("bonbon.storage.endpoint", () -> endpoint);
            registry.add("bonbon.storage.access-key", () -> "test");
            registry.add("bonbon.storage.secret-key", () -> "test");
            registry.add("bonbon.storage.region", () -> "us-east-1");
            registry.add("bonbon.storage.public-base-url", () -> endpoint + "/bonbon-public");
        };
    }
}
