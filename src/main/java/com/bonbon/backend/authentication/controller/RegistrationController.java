package com.bonbon.backend.authentication.controller;

import java.util.Optional;

import com.bonbon.backend.authentication.dto.EmailRequest;
import com.bonbon.backend.authentication.dto.RegisterRequest;
import com.bonbon.backend.authentication.dto.RegisterResponse;
import com.bonbon.backend.authentication.dto.StatusResponse;
import com.bonbon.backend.authentication.service.EmailVerificationService;
import com.bonbon.backend.authentication.service.RegistrationService;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class RegistrationController {

    private final RegistrationService registration;
    private final EmailVerificationService verification;

    RegistrationController(RegistrationService registration, EmailVerificationService verification) {
        this.registration = registration;
        this.verification = verification;
    }

    /** Public; when called with a valid access token it adds the role to that signed-in identity. */
    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        RegisterResponse response = registration.register(request,
                Optional.ofNullable(jwt).map(CurrentPrincipal::from), ClientContext.from(http));
        HttpStatus status = response.status() == RegisterResponse.Status.VERIFICATION_SENT ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /** GET so a plain click in a mail client works; safe to repeat. */
    @GetMapping("/verify-email")
    StatusResponse verifyEmail(@RequestParam String token) {
        return new StatusResponse(verification.verify(token).name());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        verification.resend(request.email(), request.captchaToken(), ClientContext.from(http).ip());
        return new StatusResponse("IF_NEEDED_SENT");
    }
}
