package com.bonbon.backend.notification.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.notification.dto.PreferenceRequests;
import com.bonbon.backend.notification.dto.PreferenceViews;
import com.bonbon.backend.notification.service.PreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.NOTIFICATIONS)
@RestController
@RequestMapping("/api/me")
class NotificationPreferenceController {

    private final PreferenceService preferences;

    NotificationPreferenceController(PreferenceService preferences) {
        this.preferences = preferences;
    }

    @Operation(operationId = "getNotificationPreferences", summary = "Tuỳ chọn thông báo", description = "Mọi nhóm thông báo dành cho vai trò đang dùng, giá trị đang áp dụng của từng kênh, nhóm nào bị khoá (không tắt được) và giờ yên lặng nếu có. Nhóm chưa được đổi trả về giá trị mặc định.")
    @ApiError(status = 403, code = "NOT_A_RECIPIENT", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @GetMapping("/notification-preferences")
    PreferenceViews.Settings get(CurrentPrincipal principal) {
        return preferences.view(principal);
    }

    @Operation(operationId = "updateNotificationPreferences", summary = "Đổi tuỳ chọn thông báo", description = "Bật hoặc tắt từng kênh của từng nhóm; tất cả thay đổi cùng được áp dụng hoặc cùng bị từ chối. Đặt lại đúng giá trị mặc định thì tuỳ chọn được xoá. Nhóm bị khoá (đơn cần chú ý, bảo mật) không bao giờ đổi được, kể cả khi ứng dụng bị sửa.")
    @ApiError(status = 422, code = "CATEGORY_LOCKED", when = "Có thay đổi cho nhóm không tắt được.")
    @ApiError(status = 400, code = "UNKNOWN_CATEGORY", when = "Nhóm không tồn tại hoặc không dành cho vai trò đang dùng.")
    @ApiError(status = 400, code = "CHANNEL_NOT_AVAILABLE", when = "Nhóm này không có kênh đó (ví dụ email cho tiến trình đơn).")
    @ApiError(status = 403, code = "NOT_A_RECIPIENT", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @PutMapping("/notification-preferences")
    PreferenceViews.Settings update(CurrentPrincipal principal, @Valid @RequestBody PreferenceRequests.Update request) {
        return preferences.update(principal, request);
    }

    @Operation(operationId = "setQuietHours", summary = "Đặt giờ yên lặng", description = "Trong khung giờ này thông báo đẩy của các nhóm tắt được bị giữ lại (vẫn có trong ứng dụng); nhóm không tắt được bỏ qua giờ yên lặng. Khung qua nửa đêm có giờ kết thúc nhỏ hơn giờ bắt đầu. Bỏ trống `start` và `end` để xoá.")
    @ApiError(status = 400, code = "QUIET_HOURS_INCOMPLETE", when = "Chỉ có một trong `start` và `end`.")
    @ApiError(status = 400, code = "QUIET_HOURS_EMPTY", when = "`start` bằng `end`.")
    @ApiError(status = 400, code = "TIME_ZONE_INVALID", when = "Múi giờ không hợp lệ.")
    @ApiError(status = 403, code = "NOT_A_RECIPIENT", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @PutMapping("/quiet-hours")
    PreferenceViews.Settings quietHours(CurrentPrincipal principal, @Valid @RequestBody PreferenceRequests.QuietHours request) {
        return preferences.setQuietHours(principal, request);
    }
}
