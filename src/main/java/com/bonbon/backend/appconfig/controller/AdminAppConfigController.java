package com.bonbon.backend.appconfig.controller;

import java.time.Instant;

import com.bonbon.backend.appconfig.dto.AppConfigRequests;
import com.bonbon.backend.appconfig.dto.AppConfigViews;
import com.bonbon.backend.appconfig.service.AppConfigService;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_APP_CONFIG, description = "Quản trị viên đặt bản app tối thiểu và bật chế độ bảo trì. Quyền hẹp (`app-config:write`). Mỗi thay đổi cần lý do, cần đăng nhập trong 10 phút gần nhất và được ghi lại. Nâng bản tối thiểu chỉ sau khi bản mới đã lên cả hai cửa hàng ứng dụng: người bán không mở được app thì không nhận được đơn.")
@RestController
@RequestMapping("/api/admin/app-config")
@PreAuthorize("hasAuthority('app-config:write')")
@Validated
class AdminAppConfigController {

    private final AppConfigService config;

    AdminAppConfigController(AppConfigService config) {
        this.config = config;
    }

    @Operation(operationId = "getAdminAppConfig", summary = "Xem cấu hình app", description = "Bản tối thiểu và mới nhất của từng nền tảng, và trạng thái bảo trì.")
    @GetMapping
    AppConfigViews.Admin get() {
        return config.admin();
    }

    @Operation(operationId = "setAppVersionPolicy", summary = "Đặt bản app tối thiểu", description = "`platform` là ANDROID hoặc IOS. Các bản thấp hơn bản tối thiểu nhận 426 ở mọi lệnh gọi (trừ đăng nhập và cấu hình) và thấy màn hình bắt cập nhật. Bản mới nhất chỉ làm app nhắc cập nhật.")
    @ApiError(status = 400, code = "PLATFORM_INVALID", when = "`platform` không phải ANDROID hoặc IOS.")
    @ApiError(status = 400, code = "LATEST_BELOW_MINIMUM", when = "Bản mới nhất thấp hơn bản tối thiểu.")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Đăng nhập đã quá 10 phút.")
    @PutMapping("/version-policy/{platform}")
    AppConfigViews.Admin setVersionPolicy(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @PathVariable String platform,
            @Valid @RequestBody AppConfigRequests.VersionPolicy request) {
        return config.setVersionPolicy(principal, authTime(jwt), platform, request);
    }

    @Operation(operationId = "setMaintenance", summary = "Đặt chế độ bảo trì", description = "`NONE` tắt; `READ_ONLY` chỉ cho xem, từ chối mọi thao tác ghi; `FULL` từ chối mọi thứ trừ cấu hình app, kiểm tra sức khoẻ, đăng nhập quản trị và webhook thanh toán. Có thể hẹn giờ bắt đầu và kết thúc. Quản trị viên vẫn dùng được API quản trị để tắt. Các tác vụ định kỳ (hết hạn đơn, tự giao) vẫn chạy và, trong lúc bảo trì, không bị tính là lỗi của quán.")
    @ApiError(status = 400, code = "MAINTENANCE_WINDOW_INVALID", when = "Giờ kết thúc không sau giờ bắt đầu.")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Đăng nhập đã quá 10 phút.")
    @PutMapping("/maintenance")
    AppConfigViews.Admin setMaintenance(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AppConfigRequests.Maintenance request) {
        return config.setMaintenance(principal, authTime(jwt), request);
    }

    private static Instant authTime(Jwt jwt) {
        Object claim = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        return claim instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
    }
}
