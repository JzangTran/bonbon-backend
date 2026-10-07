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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.SESSION, description = "Đăng xuất, đăng xuất mọi thiết bị, quên và đặt lại mật khẩu.")
@RestController
@RequestMapping("/api/auth")
class SessionController {

    private final SessionService sessions;
    private final PasswordRecoveryService recovery;

    SessionController(SessionService sessions, PasswordRecoveryService recovery) {
        this.sessions = sessions;
        this.recovery = recovery;
    }

    @Operation(operationId = "logout", summary = "Đăng xuất", description = "Thu hồi phiên hiện tại và `refreshToken` gửi kèm.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Không có access token hợp lệ.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody(required = false) PasswordRequests.Logout body, @AuthenticationPrincipal Jwt jwt) {
        sessions.logout(requireSignedIn(jwt), body == null ? null : body.refreshToken());
    }

    @Operation(operationId = "logoutEverywhere", summary = "Đăng xuất khỏi mọi thiết bị", description = "Thu hồi mọi phiên của tài khoản, kể cả thiết bị này. Tài khoản có mật khẩu phải nhập lại mật khẩu.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Không có access token hợp lệ.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu không đúng.")
    @ApiError(status = 403, code = "REAUTHENTICATION_REQUIRED", when = "Phiên đăng nhập đã lâu: đăng nhập lại rồi thử.")
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutAll(@RequestBody(required = false) PasswordRequests.LogoutAll body, @AuthenticationPrincipal Jwt jwt) {
        sessions.logoutEverywhere(requireSignedIn(jwt), body == null ? null : body.password());
    }

    @Operation(operationId = "forgotPassword", summary = "Quên mật khẩu", description = "Gửi thư đặt lại mật khẩu nếu email có tài khoản; luôn trả 202 để không tiết lộ tài khoản.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        recovery.requestReset(request.email(), ClientContext.from(http).ip());
        return new StatusResponse("IF_EXISTS_SENT");
    }

    @Operation(operationId = "resetPassword", summary = "Đặt lại mật khẩu", description = "Dùng mã trong thư đặt lại mật khẩu. Mọi phiên cũ bị đăng xuất.")
    @ApiError(status = 400, code = "INVALID_OR_EXPIRED_TOKEN", when = "Mã sai, đã dùng hoặc đã hết hạn.")
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@Valid @RequestBody PasswordRequests.SetPassword request) {
        recovery.resetPassword(request.token(), request.newPassword());
    }

    /** The link an administrator created by another administrator receives to set the first password. */
    @Operation(operationId = "setInitialPassword", summary = "Đặt mật khẩu lần đầu", description = "Dành cho quản trị viên mới được tạo, dùng mã trong thư mời.")
    @ApiError(status = 400, code = "INVALID_OR_EXPIRED_TOKEN", when = "Mã sai, đã dùng hoặc đã hết hạn.")
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
