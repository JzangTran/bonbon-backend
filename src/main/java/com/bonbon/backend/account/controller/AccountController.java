package com.bonbon.backend.account.controller;

import java.time.Instant;

import com.bonbon.backend.authentication.IssuedSession;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.UserProfileService;
import com.bonbon.backend.authentication.UserProfileService.Profile;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Self-service only: authenticated, and always the caller's own row (no id in the URL to spoof). */
@Tag(name = ApiTags.ACCOUNT, description = "Hồ sơ của chính người đang đăng nhập (không có id trên đường dẫn).")
@RestController
@RequestMapping("/api/account")
class AccountController {

    /** Vietnamese mobile number: 0 or +84 followed by 9 digits starting 3, 5, 7, 8 or 9. */
    static final String VN_MOBILE = "^(0|\\+84)(3|5|7|8|9)\\d{8}$";

    record UpdateProfileRequest(@Size(min = 1, max = 100) String name,
            @Pattern(regexp = VN_MOBILE + "|^$", message = "Số điện thoại di động Việt Nam không hợp lệ") String phone) {
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    private final UserProfileService profiles;

    AccountController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @Operation(operationId = "getMyProfile", summary = "Xem hồ sơ của tôi", description = "Tên, email, số điện thoại, các vai trò đang giữ và việc tài khoản có mật khẩu hay chỉ đăng nhập bằng Google.")
    @ApiError(status = 404, code = "USER_NOT_FOUND", when = "Tài khoản không còn tồn tại.")
    @GetMapping("/me")
    Profile me(CurrentPrincipal principal) {
        return profiles.get(principal.id());
    }

    @Operation(operationId = "updateMyProfile", summary = "Sửa hồ sơ", description = "Chỉ trường nào gửi lên mới đổi. Số điện thoại là số di động Việt Nam (bắt đầu bằng 0 hoặc +84); gửi chuỗi rỗng để xoá. Số điện thoại chỉ để liên lạc, không được xác thực.")
    @ApiError(status = 404, code = "USER_NOT_FOUND", when = "Tài khoản không còn tồn tại.")
    @PatchMapping("/me")
    Profile update(CurrentPrincipal principal, @Valid @RequestBody UpdateProfileRequest request) {
        return profiles.update(principal.id(), request.name(), request.phone());
    }

    /** Returns a fresh token pair: every other device is signed out, this one continues. */
    @Operation(operationId = "changeMyPassword", summary = "Đổi mật khẩu", description = "Cần mật khẩu hiện tại; mật khẩu mới 8–100 ký tự. Mọi thiết bị khác bị đăng xuất, thiết bị này nhận cặp token mới trong phản hồi.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Mật khẩu hiện tại không đúng.")
    @ApiError(status = 409, code = "NO_PASSWORD", when = "Tài khoản chỉ đăng nhập bằng Google, chưa có mật khẩu; dùng Quên mật khẩu để đặt.")
    @PostMapping("/change-password")
    IssuedSession changePassword(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        Object authTime = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        Instant since = authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : Instant.now();
        return profiles.changePassword(principal.id(), Role.valueOf(principal.activeRole()), since,
                request.currentPassword(), request.newPassword());
    }
}
