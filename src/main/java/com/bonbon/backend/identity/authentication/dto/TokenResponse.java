package com.bonbon.backend.identity.authentication.dto;

import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.service.TokenService.TokenPair;

public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, Role role) {

    public static TokenResponse of(TokenPair pair) {
        return new TokenResponse(pair.accessToken(), pair.refreshToken(), "Bearer", pair.expiresInSeconds(), pair.role());
    }
}
