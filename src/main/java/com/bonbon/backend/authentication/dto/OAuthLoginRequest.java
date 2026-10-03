package com.bonbon.backend.authentication.dto;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.AuthProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code token} is the provider ID token. {@code role} with {@code acceptedDocumentIds} creates the
 * identity on first sign-in, or adds that role to an existing one. {@code password} (and
 * {@code captchaToken} when asked) proves ownership of an existing email/password account to link to.
 */
public record OAuthLoginRequest(
        @NotNull AuthProvider provider,
        @NotBlank @Size(max = 4096) String token,
        Role role,
        Set<UUID> acceptedDocumentIds,
        Boolean marketingConsent,
        @Size(max = 100) String password,
        String captchaToken) {
}
