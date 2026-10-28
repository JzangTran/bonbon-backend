package com.bonbon.backend.operations.appconfig.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public final class AppConfigViews {

    private AppConfigViews() {
    }

    @Schema(name = "MaintenanceStatus")
    public record Maintenance(
            @Schema(description = "NONE, READ_ONLY (xem được, mọi thao tác ghi bị từ chối) hoặc FULL (chỉ cấu hình, kiểm tra sức khoẻ, đăng nhập quản trị và webhook thanh toán còn dùng được).") String level,
            @Schema(description = "Đang có hiệu lực ngay bây giờ: mức khác NONE, đã đến giờ bắt đầu và chưa quá giờ kết thúc.") boolean active,
            String message,
            @Schema(description = "Giờ bắt đầu; rỗng là ngay. Trước giờ này `active` là false nhưng app có thể báo trước.") Instant startsAt,
            @Schema(description = "Giờ kết thúc dự kiến; rỗng là chưa biết.") Instant endsAt) {
    }

    @Schema(name = "AppConfig", description = "Những gì app cần biết lúc khởi động và khi quay lại từ nền.")
    public record Config(
            Instant serverTime,
            @Schema(description = "ANDROID, IOS, hoặc rỗng khi client không gửi `X-App-Platform` (web).") String platform,
            @Schema(description = "Bản thấp nhất còn được hỗ trợ cho nền tảng này; rỗng khi không có nền tảng.") String minSupportedVersion,
            String latestVersion,
            String storeUrl,
            @Schema(description = "Lời nhắn kèm yêu cầu cập nhật.") String updateMessage,
            @Schema(description = "Bản đang chạy thấp hơn bản tối thiểu: app phải chặn và dẫn tới cửa hàng ứng dụng.") boolean updateRequired,
            @Schema(description = "Có bản mới hơn nhưng bản đang chạy vẫn dùng được: app nhắc một lần mỗi ngày.") boolean updateAvailable,
            Maintenance maintenance) {
    }

    @Schema(name = "AppVersionPolicy")
    public record Policy(String platform, String minSupportedVersion, String latestVersion, String storeUrl, String message, Instant updatedAt) {
    }

    @Schema(name = "AdminAppConfig")
    public record Admin(List<Policy> policies, Maintenance maintenance) {
    }
}
