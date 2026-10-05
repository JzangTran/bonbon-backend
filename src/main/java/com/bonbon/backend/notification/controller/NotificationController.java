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

/**
 * The caller's own notifications for the role they are acting as: a seller sees the shop's, a customer their own.
 * (An identity holding both roles never sees the other side's alerts mixed in.)
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** {@code unread=true} is the pending view to show after a reconnect. */
    @GetMapping
    NotificationViews.Page list(CurrentPrincipal principal, @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(principal.id(), audience(principal), unread, page, size);
    }

    /** Acknowledges one notification; repeating the call is harmless. */
    @PostMapping("/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(CurrentPrincipal principal, @PathVariable UUID id) {
        notifications.acknowledge(principal.id(), id);
    }

    @PostMapping("/ack-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledgeAll(CurrentPrincipal principal) {
        notifications.acknowledgeAll(principal.id(), audience(principal));
    }

    private static String audience(CurrentPrincipal principal) {
        return "SELLER".equals(principal.activeRole()) ? NotificationService.SHOP : NotificationService.CUSTOMER;
    }
}
