package com.bonbon.backend.notification.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public final class PreferenceViews {

    private PreferenceViews() {
    }

    @Schema(name = "NotificationChannelSetting")
    public record Channel(
            @Schema(description = "`PUSH` hoặc `EMAIL`.") String channel,
            @Schema(description = "Giá trị đang áp dụng (mặc định nếu người dùng chưa đổi).") boolean enabled,
            @Schema(description = "Giá trị mặc định của kênh này.") boolean defaultEnabled) {
    }

    @Schema(name = "NotificationCategorySetting")
    public record Category(
            @Schema(description = "ORDER_ALERTS, ACCOUNT_SECURITY, ORDER_PROGRESS, CHAT_MESSAGES hoặc SHOP_NOTICES.") String category,
            String title,
            String description,
            @Schema(description = "Nhóm không tắt được: luôn gửi, máy chủ từ chối mọi thay đổi.") boolean locked,
            List<Channel> channels) {
    }

    @Schema(name = "QuietHoursSetting", description = "Giờ yên lặng: thông báo đẩy của các nhóm tắt được bị giữ lại trong khung này. Nhóm không tắt được bỏ qua giờ yên lặng.")
    public record QuietHours(
            @Schema(description = "Giờ bắt đầu `HH:mm`.") String start,
            @Schema(description = "Giờ kết thúc `HH:mm`; nhỏ hơn giờ bắt đầu nghĩa là qua nửa đêm.") String end,
            @Schema(description = "Múi giờ IANA.") String timeZone) {
    }

    @Schema(name = "NotificationPreferences")
    public record Settings(
            @Schema(description = "Các nhóm dành cho vai trò đang dùng.") List<Category> categories,
            @Schema(description = "Rỗng khi không đặt giờ yên lặng.") QuietHours quietHours) {
    }

    @Schema(name = "PushDeviceView")
    public record Device(java.util.UUID id, String platform, String appVersion, java.time.Instant lastSeenAt) {
    }

    @Schema(name = "PushDeviceList", description = "Thiết bị đang nhận thông báo đẩy của tài khoản.")
    public record Devices(List<Device> items) {
    }
}
