package com.bonbon.backend.identity.authentication.dto;

/** The phone app sends its refresh token here; the web app sends none and the HttpOnly cookie is used. */
public record RefreshRequest(String refreshToken) {
}
