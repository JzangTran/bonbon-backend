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
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.LOGIN, description = "Đăng nhập bằng email/mật khẩu hoặc Google, chọn và đổi vai trò. Mỗi phiên chỉ có một vai trò.")
@RestController
@RequestMapping("/api/auth")
class LoginController {

    private final LoginService login;
    private final OAuthLoginService oauth;

    LoginController(LoginService login, OAuthLoginService oauth) {
        this.login = login;
        this.oauth = oauth;
    }

    @Operation(operationId = "login", summary = "Đăng nhập bằng email", description = "Nếu tài khoản giữ cả vai trò khách và người bán, phản hồi có `needsRoleSelection` và `roleToken` thay vì token: gọi tiếp `select-role`. Sai mật khẩu nhiều lần thì phải kèm `captchaToken`.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Email hoặc mật khẩu không đúng.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken` (reCAPTCHA).")
    @ApiError(status = 403, code = "EMAIL_NOT_VERIFIED", when = "Email chưa được xác thực; có thể gửi lại thư xác thực.")
    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return login.login(request.email(), request.password(), request.captchaToken(), ClientContext.from(http).ip());
    }

    /**
     * Sign in with a Google ID token. 409 OAUTH_SIGNUP_REQUIRED asks for role and consent (new identity);
     * 409 ACCOUNT_EXISTS_LINK_REQUIRED with linkMethod PASSWORD asks for the existing account password.
     */
    @Operation(operationId = "loginWithGoogle", summary = "Đăng nhập bằng Google", description = "Gửi ID token của Google. Email chưa có tài khoản thì cần thêm vai trò và văn bản đã đồng ý để tạo tài khoản. Email đã có tài khoản bằng mật khẩu và đã xác thực thì cần mật khẩu đó để liên kết (không bao giờ liên kết ngầm). Email có tài khoản nhưng chưa từng xác thực thì Google được coi là bằng chứng sở hữu: email thành đã xác thực, mật khẩu cũ bị xóa (có thể do người khác đặt trước), Google được liên kết và người dùng đăng nhập luôn; đặt lại mật khẩu bằng \"Quên mật khẩu\" nếu muốn dùng.")
    @ApiError(status = 401, code = "INVALID_OAUTH_TOKEN", when = "ID token không hợp lệ hoặc không dành cho bonbon.")
    @ApiError(status = 400, code = "OAUTH_EMAIL_UNVERIFIED", when = "Google chưa xác thực email này.")
    @ApiError(status = 400, code = "PROVIDER_NOT_SUPPORTED", when = "Nhà cung cấp chưa được hỗ trợ (hiện chỉ có Google).")
    @ApiError(status = 400, code = "ROLE_NOT_SELF_REGISTERABLE", when = "Vai trò này không tự đăng ký được.")
    @ApiError(status = 409, code = "OAUTH_SIGNUP_REQUIRED", when = "Chưa có tài khoản cho email này: chọn vai trò và đồng ý điều khoản rồi gửi lại; có `email`, `name`.")
    @ApiError(status = 409, code = "ACCOUNT_EXISTS_LINK_REQUIRED", when = "Email đã có tài khoản: `linkMethod` cho biết cần làm gì (`PASSWORD`: nhập mật khẩu rồi gửi lại; `SIGN_IN_FIRST`: đăng nhập bằng phương thức cũ rồi liên kết).")
    @ApiError(status = 409, code = "EMAIL_ALREADY_REGISTERED", when = "Email vừa được đăng ký bởi yêu cầu khác; thử lại.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu để liên kết không đúng.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken` (reCAPTCHA).")
    @ApiError(status = 400, code = "CONSENT_REQUIRED", when = "Chưa đồng ý đủ các văn bản bắt buộc (điều khoản của vai trò và chính sách bảo mật).")
    @ApiError(status = 409, code = "LEGAL_DOCUMENTS_CHANGED", when = "Văn bản vừa có phiên bản mới; tải lại và đồng ý lại.")
    @PostMapping("/login/oauth")
    LoginResponse loginWithProvider(@Valid @RequestBody OAuthLoginRequest request, HttpServletRequest http) {
        return oauth.login(request, ClientContext.from(http));
    }

    @Operation(operationId = "selectRole", summary = "Chọn vai trò sau đăng nhập", description = "Dùng `roleToken` từ bước đăng nhập (hiệu lực ngắn) để lấy token cho vai trò đã chọn.")
    @ApiError(status = 400, code = "INVALID_ROLE_TOKEN", when = "`roleToken` sai hoặc đã hết hạn; đăng nhập lại.")
    @ApiError(status = 403, code = "ROLE_NOT_HELD", when = "Tài khoản không giữ vai trò này.")
    @PostMapping("/select-role")
    LoginResponse selectRole(@Valid @RequestBody RoleRequest request) {
        return login.selectRole(request.roleToken(), request.role());
    }

    /** Needs a signed-in session (the /api/auth/** paths are public, so the check is explicit here). */
    @Operation(operationId = "switchRole", summary = "Đổi vai trò", description = "Đổi giữa khách và người bán trong cùng một tài khoản; nhận cặp token mới cho vai trò kia.")
    @ApiError(status = 401, code = "UNAUTHENTICATED", when = "Cần đăng nhập.")
    @ApiError(status = 403, code = "ROLE_NOT_HELD", when = "Tài khoản không giữ vai trò này.")
    @PostMapping("/switch-role")
    LoginResponse switchRole(@Valid @RequestBody RoleRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Vui lòng đăng nhập.");
        }
        return login.switchRole(jwt, request.role());
    }
}
