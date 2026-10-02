package com.bonbon.backend.authentication.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.security.TokenRevocationCheck;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Runs on every authenticated request after signature and expiry: rejects a logged-out token (blacklisted
 * jti) and any token issued before the user's tokens_valid_after (password change/reset, logout-all).
 */
@Component
class AccessTokenRevocationCheck implements TokenRevocationCheck {

    private final TokenRevocationStore store;
    private final UserRepository users;

    AccessTokenRevocationCheck(TokenRevocationStore store, UserRepository users) {
        this.store = store;
        this.users = users;
    }

    @Override
    public boolean isRevoked(Jwt jwt) {
        if (jwt.getId() != null && store.isBlacklisted(jwt.getId())) {
            return true;
        }
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            return true;
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        Instant validAfter = store.cachedValidAfter(userId).orElseGet(() -> loadAndCache(userId));
        // iat has second precision; comparing at second precision keeps a token issued in the same
        // second as the stamp (the caller's fresh pair after a password change) valid.
        return validAfter != null && issuedAt.isBefore(validAfter.truncatedTo(ChronoUnit.SECONDS));
    }

    private Instant loadAndCache(UUID userId) {
        return users.findById(userId).map(u -> {
            store.cacheValidAfter(userId, u.getTokensValidAfter());
            return u.getTokensValidAfter();
        }).orElse(Instant.MAX);
    }
}
