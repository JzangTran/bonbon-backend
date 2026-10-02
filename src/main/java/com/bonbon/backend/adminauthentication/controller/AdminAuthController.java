package com.bonbon.backend.adminauthentication.controller;

import com.bonbon.backend.authentication.AdminAccountService;
import com.bonbon.backend.authentication.IssuedSession;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController {

    record AdminLoginRequest(@NotBlank String email, @NotBlank String password, String captchaToken) {
    }

    record AdminLogoutRequest(String refreshToken) {
    }

    private final AdminAccountService admins;

    AdminAuthController(AdminAccountService admins) {
        this.admins = admins;
    }

    @PostMapping("/login")
    IssuedSession login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
        return admins.signIn(request.email(), request.password(), request.captchaToken(), ClientContext.from(http).ip());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody(required = false) AdminLogoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        admins.signOut(jwt, request == null ? null : request.refreshToken());
    }
}
