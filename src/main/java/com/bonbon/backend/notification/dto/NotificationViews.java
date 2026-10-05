package com.bonbon.backend.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.notification.entity.Notification;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** The in-app notification list. */
public final class NotificationViews {

    private NotificationViews() {
    }

    /** {@code acknowledgedAt} null means unread. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "NotificationItem")
    public record Item(UUID id, String type, String title, String body, UUID orderId, Long orderNumber, Instant createdAt, Instant acknowledgedAt) {

        public static Item of(Notification n) {
            return new Item(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getOrderId(), n.getOrderNumber(), n.getCreatedAt(), n.getAcknowledgedAt());
        }
    }

    /** {@code unread} counts every unread notification of the audience, not just this page. */
    @Schema(name = "NotificationPage")
    public record Page(List<Item> items, long unread, int page, int size, long total) {
    }
}
