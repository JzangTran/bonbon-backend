package com.bonbon.backend.authentication.controller;

import com.bonbon.backend.authentication.dto.RefreshRequest;
import com.bonbon.backend.authentication.dto.TokenResponse;
import com.bonbon.backend.authentication.service.TokenService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class TokenController {

    private final TokenService tokens;

    TokenController(TokenService tokens) {
        this.tokens = tokens;
    }

    /** Exchanges a refresh token for a new pair; the presented token is revoked (rotation). */
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.of(tokens.refresh(request.refreshToken()));
    }
}
