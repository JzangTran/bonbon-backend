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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.ADMIN_AUTH, description = "Đăng nhập riêng cho quản trị viên (không dùng chung tài khoản khách hay người bán).")
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

    @Operation(operationId = "adminLogin", summary = "Đăng nhập quản trị", description = "Email và mật khẩu của quản trị viên. Sai nhiều lần thì phải kèm reCAPTCHA.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Email hoặc mật khẩu không đúng.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken` (reCAPTCHA).")
    @PostMapping("/login")
    IssuedSession login(@Valid @RequestBody AdminLoginRequest request, HttpServletRequest http) {
        return admins.signIn(request.email(), request.password(), request.captchaToken(), ClientContext.from(http).ip());
    }

    @Operation(operationId = "adminLogout", summary = "Đăng xuất quản trị", description = "Thu hồi phiên hiện tại; gửi kèm `refreshToken` để thu hồi luôn token đó.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Không có access token hợp lệ.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody(required = false) AdminLogoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        admins.signOut(jwt, request == null ? null : request.refreshToken());
    }
}
