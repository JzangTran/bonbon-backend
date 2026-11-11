package com.bonbon.backend.operations.appconfig.controller;

import com.bonbon.backend.operations.appconfig.dto.AppConfigViews;
import com.bonbon.backend.operations.appconfig.service.AppConfigService;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.APP_CONFIG, description = "Cấu hình công khai cho app: bản tối thiểu còn được hỗ trợ và chế độ bảo trì. App gọi lúc khởi động và khi quay lại từ nền sau hơn 10 phút. Không cần đăng nhập và không bao giờ bị chặn bởi bảo trì hay bản cũ, để màn hình chặn luôn hiện được.")
@RestController
@RequestMapping("/api/app")
class AppConfigController {

    private final AppConfigService config;

    AppConfigController(AppConfigService config) {
        this.config = config;
    }

    @Operation(operationId = "getAppConfig", summary = "Cấu hình app", description = "Client gửi `X-App-Platform` (ANDROID hoặc IOS) và `X-App-Version` trên mọi lệnh gọi. Bản thấp hơn bản tối thiểu thì `updateRequired` là true (các lệnh gọi khác trả 426 `APP_UPDATE_REQUIRED` kèm `storeUrl`); thấp hơn bản mới nhất thì `updateAvailable` là true. Web không gửi hai header này nên không bị chặn theo phiên bản. `maintenance.active` cho biết hệ thống đang bảo trì: FULL từ chối mọi thứ (503 `MAINTENANCE`), READ_ONLY chỉ cho đọc (503 `MAINTENANCE_READ_ONLY` với thao tác ghi). Webhook thanh toán và đăng nhập quản trị luôn dùng được.")
    @GetMapping("/config")
    AppConfigViews.Config config(@RequestHeader(value = "X-App-Platform", required = false) String platform, @RequestHeader(value = "X-App-Version", required = false) String version) {
        return config.config(platform == null || platform.isBlank() ? null : platform.strip().toUpperCase(), version);
    }
}
