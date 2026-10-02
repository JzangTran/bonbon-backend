package com.bonbon.backend.authentication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code captchaToken} is needed only after repeated failures (the response says CAPTCHA_REQUIRED). */
public record LoginRequest(@NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 100) String password,
        String captchaToken) {
}
