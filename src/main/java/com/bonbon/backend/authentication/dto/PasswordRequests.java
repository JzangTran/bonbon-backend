package com.bonbon.backend.authentication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies of the session and password endpoints. */
public final class PasswordRequests {

    private PasswordRequests() {
    }

    public record Logout(String refreshToken) {
    }

    public record LogoutAll(String password) {
    }

    /** Reset or initial password: same 8–100 character rule as registration. */
    public record SetPassword(@NotBlank String token, @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }
}
