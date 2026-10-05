package com.bonbon.backend.notification.controller;

import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.notification.dto.NotificationViews;
import com.bonbon.backend.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The caller's own notifications for the role they are acting as: a seller sees the shop's, a customer their own.
 * (An identity holding both roles never sees the other side's alerts mixed in.)
 */
@Tag(name = ApiTags.NOTIFICATIONS, description = "Thông báo trong ứng dụng của vai trò đang dùng: người bán thấy thông báo của quán, khách thấy của mình.")
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** {@code unread=true} is the pending view to show after a reconnect. */
    @Operation(operationId = "listNotifications", summary = "Danh sách thông báo", description = "Mới nhất trước; `unread=true` là danh sách còn chờ, nên gọi sau khi kết nối lại. `unread` trong phản hồi là tổng số chưa đọc.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    NotificationViews.Page list(CurrentPrincipal principal, @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(principal.id(), audience(principal), unread, page, size);
    }

    /** Acknowledges one notification; repeating the call is harmless. */
    @Operation(operationId = "acknowledgeNotification", summary = "Đánh dấu đã đọc", description = "Gọi lại nhiều lần vẫn an toàn.")
    @ApiError(status = 404, code = "NOTIFICATION_NOT_FOUND", when = "Không có thông báo với id này.")
    @PostMapping("/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(CurrentPrincipal principal, @PathVariable UUID id) {
        notifications.acknowledge(principal.id(), id);
    }

    @Operation(operationId = "acknowledgeAllNotifications", summary = "Đánh dấu tất cả đã đọc", description = "Chỉ trong vai trò đang dùng.")
    @PostMapping("/ack-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledgeAll(CurrentPrincipal principal) {
        notifications.acknowledgeAll(principal.id(), audience(principal));
    }

    private static String audience(CurrentPrincipal principal) {
        return "SELLER".equals(principal.activeRole()) ? NotificationService.SHOP : NotificationService.CUSTOMER;
    }
}
