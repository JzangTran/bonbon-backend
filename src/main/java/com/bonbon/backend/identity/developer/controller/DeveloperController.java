package com.bonbon.backend.identity.developer.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.developer.dto.DeveloperDtos;
import com.bonbon.backend.identity.developer.service.DeveloperAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The developer portal: ask for access, sign in once approved; administrators approve, reject or revoke. */
@Tag(name = ApiTags.DEVELOPERS)
@RestController
class DeveloperController {

    private final DeveloperAccessService access;

    DeveloperController(DeveloperAccessService access) {
        this.access = access;
    }

    @Operation(operationId = "requestDeveloperAccess", summary = "Xin truy cập tài liệu API",
            description = "Gửi đội ngũ phát triển. Không ai tự đăng ký được: quản trị viên duyệt rồi mới đăng nhập được trang dành cho lập trình viên. Ba yêu cầu một ngày mỗi địa chỉ IP.")
    @ApiError(status = 409, code = "REQUEST_PENDING", when = "Email này đã có yêu cầu đang chờ duyệt.")
    @ApiError(status = 409, code = "ALREADY_DEVELOPER", when = "Email này đã được cấp quyền.")
    @ApiError(status = 429, code = "TOO_MANY_REQUESTS", when = "Đã gửi 3 yêu cầu hôm nay.")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Thiếu hoặc sai mã reCAPTCHA.")
    @PostMapping("/api/developer/access-requests")
    @ResponseStatus(HttpStatus.CREATED)
    DeveloperDtos.Received request(@Valid @RequestBody DeveloperDtos.Create request, HttpServletRequest http) {
        return access.request(request, ClientContext.from(http).ip());
    }

    @Operation(operationId = "developerLogin", summary = "Đăng nhập trang dành cho lập trình viên",
            description = "Chỉ tài khoản đã được duyệt. Token có quyền `api-docs:read` để đọc `GET /v3/api-docs`.")
    @ApiError(status = 401, code = "INVALID_CREDENTIALS", when = "Sai email hoặc mật khẩu, hoặc tài khoản chưa được duyệt (không nói cái nào).")
    @ApiError(status = 400, code = "CAPTCHA_REQUIRED", when = "Sai nhiều lần liên tiếp: gửi lại kèm `captchaToken`.")
    @PostMapping("/api/developer/auth/login")
    LoginResponse login(@Valid @RequestBody DeveloperDtos.Login request, HttpServletRequest http) {
        return access.login(request, ClientContext.from(http).ip());
    }

    @Operation(operationId = "listDeveloperAccessRequests", summary = "Yêu cầu truy cập API", description = "Mặc định các yêu cầu đang chờ, mới nhất trước.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải PENDING, APPROVED, REJECTED, REVOKED hoặc ALL.")
    @GetMapping("/api/admin/developer-access-requests")
    @PreAuthorize("hasAuthority('developer-access:decide')")
    DeveloperDtos.Page queue(@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return access.queue(status, page, size);
    }

    @Operation(operationId = "approveDeveloperAccess", summary = "Duyệt yêu cầu truy cập API",
            description = "Email mới thì tạo tài khoản và gửi liên kết đặt mật khẩu; email đã có tài khoản khách hoặc người bán thì thêm vai trò lập trình viên. Người gửi nhận email báo kết quả.")
    @ApiError(status = 404, code = "ACCESS_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "ACCESS_REQUEST_DECIDED", when = "Yêu cầu đã được xử lý.")
    @ApiError(status = 409, code = "ADMIN_ACCOUNT", when = "Email là tài khoản quản trị.")
    @PostMapping("/api/admin/developer-access-requests/{id}/approve")
    @PreAuthorize("hasAuthority('developer-access:decide')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(CurrentPrincipal principal, @PathVariable UUID id) {
        access.approve(principal.id(), id);
    }

    @Operation(operationId = "rejectDeveloperAccess", summary = "Từ chối yêu cầu truy cập API", description = "Người gửi nhận email kèm lý do.")
    @ApiError(status = 404, code = "ACCESS_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "ACCESS_REQUEST_DECIDED", when = "Yêu cầu đã được xử lý.")
    @PostMapping("/api/admin/developer-access-requests/{id}/reject")
    @PreAuthorize("hasAuthority('developer-access:decide')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody DeveloperDtos.Reason request) {
        access.reject(principal.id(), id, request.reason());
    }

    @Operation(operationId = "revokeDeveloperAccess", summary = "Thu hồi quyền truy cập API",
            description = "Bỏ vai trò lập trình viên và đăng xuất mọi phiên của tài khoản; vai trò khách hoặc người bán vẫn giữ.")
    @ApiError(status = 404, code = "ACCESS_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "NOT_APPROVED", when = "Yêu cầu chưa được duyệt.")
    @PostMapping("/api/admin/developer-access-requests/{id}/revoke")
    @PreAuthorize("hasAuthority('developer-access:decide')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody DeveloperDtos.Reason request) {
        access.revoke(principal.id(), id, request.reason());
    }
}
