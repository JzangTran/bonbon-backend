package com.bonbon.backend.common.security;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HS256 signing secret for access tokens, read from {@code BONBON_JWT_SECRET}; never committed.
 */
@ConfigurationProperties("bonbon.security.jwt")
public record JwtProperties(String secret) {

    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "bonbon.security.jwt.secret (env BONBON_JWT_SECRET) must be set to at least "
                            + MIN_SECRET_BYTES + " bytes for HS256");
        }
    }

    byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
