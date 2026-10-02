package com.bonbon.backend.authentication;

import java.util.UUID;

/** Tokens and identity of a freshly signed-in session, for modules outside authentication. */
public record IssuedSession(String accessToken, String refreshToken, long expiresIn, UUID userId, String email,
        String name, Role role) {
}
