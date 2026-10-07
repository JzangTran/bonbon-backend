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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.REGISTRATION, description = "Tạo tài khoản khách hoặc người bán và xác thực email.")
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
    @Operation(operationId = "register", summary = "Đăng ký", description = "Tạo tài khoản và gửi thư xác thực (201). Nếu gọi kèm access token của một tài khoản đã xác thực, vai trò mới được thêm vào chính tài khoản đó (200) thay vì tạo tài khoản thứ hai.")
    @ApiError(status = 400, code = "NAME_REQUIRED", when = "Thiếu họ tên khi tạo tài khoản mới.")
    @ApiError(status = 400, code = "PASSWORD_REQUIRED", when = "Thiếu mật khẩu khi tạo tài khoản mới.")
    @ApiError(status = 400, code = "ROLE_NOT_SELF_REGISTERABLE", when = "Vai trò này không tự đăng ký được.")
    @ApiError(status = 400, code = "CAPTCHA_INVALID", when = "reCAPTCHA không hợp lệ.")
    @ApiError(status = 409, code = "EMAIL_ALREADY_REGISTERED", when = "Email đã có tài khoản: đăng nhập rồi thêm vai trò.")
    @ApiError(status = 409, code = "IDENTITY_NOT_VERIFIED", when = "Tài khoản chưa xác thực email nên chưa thêm vai trò được.")
    @ApiError(status = 409, code = "ROLE_ALREADY_HELD", when = "Tài khoản đã có vai trò này.")
    @ApiError(status = 409, code = "SIGN_IN_TO_ADD_ROLE", when = "Đăng nhập trước để thêm vai trò.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu xác nhận không đúng.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @ApiError(status = 400, code = "CONSENT_REQUIRED", when = "Chưa đồng ý đủ các văn bản bắt buộc (điều khoản của vai trò và chính sách bảo mật).")
    @ApiError(status = 409, code = "LEGAL_DOCUMENTS_CHANGED", when = "Văn bản vừa có phiên bản mới; tải lại và đồng ý lại.")
    @ApiResponse(responseCode = "201", description = "Tài khoản mới đã tạo, thư xác thực đã gửi.", content = @Content(schema = @Schema(implementation = RegisterResponse.class)))
    @ApiResponse(responseCode = "200", description = "Đã thêm vai trò vào tài khoản đang đăng nhập.", content = @Content(schema = @Schema(implementation = RegisterResponse.class)))
    @PostMapping("/register")
    ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
            @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        RegisterResponse response = registration.register(request,
                Optional.ofNullable(jwt).map(CurrentPrincipal::from), ClientContext.from(http));
        HttpStatus status = response.status() == RegisterResponse.Status.VERIFICATION_SENT ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /** GET so a plain click in a mail client works; safe to repeat. */
    @Operation(operationId = "verifyEmail", summary = "Xác thực email", description = "Liên kết trong thư xác thực gọi API này (GET để bấm trực tiếp từ hộp thư); gọi lại nhiều lần vẫn an toàn.")
    @ApiError(status = 400, code = "INVALID_OR_EXPIRED_TOKEN", when = "Liên kết sai hoặc đã hết hạn; gửi lại thư xác thực.")
    @GetMapping("/verify-email")
    StatusResponse verifyEmail(@RequestParam String token) {
        return new StatusResponse(verification.verify(token).name());
    }

    @Operation(operationId = "resendVerificationEmail", summary = "Gửi lại thư xác thực", description = "Luôn trả 202 dù email có tồn tại hay không (không tiết lộ tài khoản). Gửi nhiều lần thì phải kèm reCAPTCHA.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken` (reCAPTCHA).")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Gửi quá nhiều lần trong thời gian ngắn; đợi rồi thử lại.")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    StatusResponse resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        verification.resend(request.email(), request.captchaToken(), ClientContext.from(http).ip());
        return new StatusResponse("IF_NEEDED_SENT");
    }
}
