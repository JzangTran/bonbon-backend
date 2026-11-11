package com.bonbon.backend.identity.authentication.dto;

import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.service.TokenService.TokenPair;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A fresh token pair. {@code refreshToken} is empty for the web app (it travels in an HttpOnly cookie instead); {@code user} comes
 * with a refresh, so the web app can rebuild who is signed in after a reload without storing it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, Role role, UserSummary user) {

    public static TokenResponse of(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds(), pair.role(), null);
    }

    public TokenResponse withUser(UserSummary summary) {
        return new TokenResponse(accessToken, refreshToken, tokenType, expiresIn, role, summary);
    }

    public TokenResponse withoutRefreshToken() {
        return new TokenResponse(accessToken, null, tokenType, expiresIn, role, user);
    }
}
