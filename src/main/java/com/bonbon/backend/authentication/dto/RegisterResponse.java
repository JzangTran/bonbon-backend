package com.bonbon.backend.authentication.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * VERIFICATION_SENT: new identity, check your email. ROLE_ADDED: the role was added; sign in again, or use
 * {@code tokens} when the request came from a signed-in session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(Status status, TokenResponse tokens) {

    public enum Status { VERIFICATION_SENT, ROLE_ADDED }
}
