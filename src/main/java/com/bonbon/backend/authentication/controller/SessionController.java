package com.bonbon.backend.authentication.controller;

import com.bonbon.backend.authentication.dto.EmailRequest;
import com.bonbon.backend.authentication.dto.PasswordRequests;
import com.bonbon.backend.authentication.dto.StatusResponse;
import com.bonbon.backend.authentication.service.PasswordRecoveryService;
import com.bonbon.backend.authentication.service.SessionService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class SessionController {

    private final SessionService sessions;
    private final PasswordRecoveryService recovery;

    SessionController(SessionService sessions, PasswordRecoveryService recovery) {
        this.sessions = sessions;
        this.recovery = recovery;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody(required = false) PasswordRequests.Logout body, @AuthenticationPrincipal Jwt jwt) {
        sessions.logout(requireSignedIn(jwt), body == null ? null : body.refreshToken());
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(@RequestBody(required = false) PasswordRequests.LogoutAll body, @AuthenticationPrincipal Jwt jwt) {
        sessions.logoutEverywhere(requireSignedIn(jwt), body == null ? null : body.password());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        recovery.requestReset(request.email(), ClientContext.from(http).ip());
        return new StatusResponse("IF_EXISTS_SENT");
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody PasswordRequests.SetPassword request) {
        recovery.resetPassword(request.token(), request.newPassword());
    }

    /** The link an administrator created by another administrator receives to set the first password. */
    @PostMapping("/set-initial-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setInitialPassword(@Valid @RequestBody PasswordRequests.SetPassword request) {
        recovery.setInitialPassword(request.token(), request.newPassword());
    }

    private static Jwt requireSignedIn(Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        return jwt;
    }
}
