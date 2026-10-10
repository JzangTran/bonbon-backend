package com.bonbon.backend.notification.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PreferenceRequests {

    private PreferenceRequests() {
    }

    @Schema(name = "NotificationPreferenceChange")
    public record Change(
            @NotNull String category,
            @Schema(description = "`PUSH` hoặc `EMAIL`.") @NotNull @Pattern(regexp = "PUSH|EMAIL", message = "Kênh phải là PUSH hoặc EMAIL.") String channel,
            @NotNull Boolean enabled) {
    }

    @Schema(name = "UpdateNotificationPreferencesRequest", description = "Tất cả thay đổi cùng được áp dụng hoặc cùng bị từ chối. Đặt về giá trị mặc định thì dòng tuỳ chọn bị xoá.")
    public record Update(@NotEmpty @Size(max = 20) List<@Valid Change> changes) {
    }

    @Schema(name = "SetQuietHoursRequest", description = "Gửi `start` và `end` để đặt giờ yên lặng, bỏ trống cả hai để xoá.")
    public record QuietHours(
            @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "Giờ phải có dạng HH:mm.") String start,
            @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "Giờ phải có dạng HH:mm.") String end,
            @Schema(description = "Múi giờ IANA; mặc định Asia/Ho_Chi_Minh.") @Size(max = 64) String timeZone) {
    }
}
