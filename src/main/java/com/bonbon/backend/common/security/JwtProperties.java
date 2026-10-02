package com.bonbon.backend.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access-token signing and lifetimes. The secret comes from {@code BONBON_JWT_SECRET} and is never committed.
 * Lifetimes follow the non-functional requirements: access 30 min, refresh 7 days with rotation.
 */
@ConfigurationProperties("bonbon.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("PT30M") Duration accessTtl,
        @DefaultValue("P7D") Duration refreshTtl) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "bonbon.security.jwt.secret (env BONBON_JWT_SECRET) must be set to at least "
                            + MIN_SECRET_BYTES + " bytes for HS256");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
