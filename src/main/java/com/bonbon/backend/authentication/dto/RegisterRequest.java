package com.bonbon.backend.authentication.dto;

import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.authentication.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code password} is required for a new identity and for adding a role while signed out; {@code name}
 * only for a new identity. {@code acceptedDocumentIds} are the exact document versions shown on the form.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @Size(min = 8, max = 100) String password,
        @Size(max = 100) String name,
        @NotNull Role role,
        Set<UUID> acceptedDocumentIds,
        Boolean marketingConsent,
        String captchaToken) {
}
