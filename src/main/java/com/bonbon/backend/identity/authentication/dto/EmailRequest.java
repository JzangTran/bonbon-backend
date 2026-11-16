package com.bonbon.backend.identity.authentication.dto;

import com.bonbon.backend.common.validation.RealEmail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRequest(@NotBlank @RealEmail @Size(max = 255) String email, @Size(max = 4096) String captchaToken) {
}
