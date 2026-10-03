package com.bonbon.backend.authentication.controller;

import com.bonbon.backend.authentication.dto.LoginRequest;
import com.bonbon.backend.authentication.dto.LoginResponse;
import com.bonbon.backend.authentication.dto.OAuthLoginRequest;
import com.bonbon.backend.authentication.dto.RoleRequest;
import com.bonbon.backend.authentication.service.LoginService;
import com.bonbon.backend.authentication.service.OAuthLoginService;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.web.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class LoginController {

    private final LoginService login;
    private final OAuthLoginService oauth;

    LoginController(LoginService login, OAuthLoginService oauth) {
        this.login = login;
        this.oauth = oauth;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return login.login(request.email(), request.password(), request.captchaToken(), ClientContext.from(http).ip());
    }

    /**
     * Sign in with a Google ID token. 409 OAUTH_SIGNUP_REQUIRED asks for role and consent (new identity);
     * 409 ACCOUNT_EXISTS_LINK_REQUIRED with linkMethod PASSWORD asks for the existing account password.
     */
    @PostMapping("/login/oauth")
    LoginResponse loginWithProvider(@Valid @RequestBody OAuthLoginRequest request, HttpServletRequest http) {
        return oauth.login(request, ClientContext.from(http));
    }

    @PostMapping("/select-role")
    LoginResponse selectRole(@Valid @RequestBody RoleRequest request) {
        return login.selectRole(request.roleToken(), request.role());
    }

    /** Needs a signed-in session (the /api/auth/** paths are public, so the check is explicit here). */
    @PostMapping("/switch-role")
    LoginResponse switchRole(@Valid @RequestBody RoleRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        return login.switchRole(jwt, request.role());
    }
}
