package com.bonbon.backend.notification.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.notification.entity.Notification;
import com.bonbon.backend.notification.entity.PushDevice;
import com.bonbon.backend.notification.gateway.PushGateway;
import com.bonbon.backend.notification.repository.NotificationRepository;
import com.bonbon.backend.notification.repository.PushDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the push for freshly stored notifications, after the order change has committed and off the request thread.
 * A failure here never touches the business change or removes the stored row; it only leaves {@code delivered_at} empty.
 */
@Component
class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final PushDeviceRepository devices;
    private final NotificationRepository notifications;
    private final PushGateway gateway;
    private final PreferenceService preferences;
    private final TransactionTemplate tx;

    PushDispatcher(PushDeviceRepository devices, NotificationRepository notifications, PushGateway gateway, PreferenceService preferences, PlatformTransactionManager transactions) {
        this.devices = devices;
        this.notifications = notifications;
        this.gateway = gateway;
        this.preferences = preferences;
        this.tx = new TransactionTemplate(transactions);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCreated(NotificationService.Created created) {
        try {
            dispatch(created.items());
        } catch (RuntimeException e) {
            log.warn("Push dispatch failed", e);
        }
    }

    void dispatch(List<Notification> items) {
        List<PushGateway.PushMessage> messages = new ArrayList<>();
        Set<UUID> reached = new HashSet<>();
        for (Notification n : items) {
            // A silenced push (or one held back by quiet hours) is still in the in-app list; an order alert is never silenced.
            if (!preferences.allows(n.getRecipientId(), NotificationCategory.of(n.getAudience(), n.getType()), "PUSH")) {
                continue;
            }
            List<PushDevice> active = tx.execute(status -> devices.findActiveByUser(n.getRecipientId()));
            for (PushDevice d : active == null ? List.<PushDevice>of() : active) {
                // Ids only: lock-screen text is visible to people other than the account holder.
                Map<String, String> data = Map.of("notificationId", n.getId().toString(), "type", n.getType(),
                        "orderId", n.getOrderId() == null ? "" : n.getOrderId().toString(), "audience", n.getAudience());
                messages.add(new PushGateway.PushMessage(d.getToken(), n.getTitle(), n.getBody(), data));
                reached.add(n.getId());
            }
        }
        if (messages.isEmpty()) {
            return;
        }
        List<String> dead = gateway.send(messages);
        tx.executeWithoutResult(status -> {
            notifications.markDelivered(reached, Instant.now());
            for (String token : dead) {
                devices.findByKindAndToken("EXPO", token).ifPresent(d -> d.mark(PushDevice.Status.INVALID));
            }
        });
    }

    /** A push that has no stored notification behind it (a chat message): ids only, to every active device of the user. */
    void sendTo(UUID userId, String title, String body, Map<String, String> data) {
        List<PushDevice> active = tx.execute(status -> devices.findActiveByUser(userId));
        if (active == null || active.isEmpty()) {
            return;
        }
        List<String> dead = gateway.send(active.stream().map(d -> new PushGateway.PushMessage(d.getToken(), title, body, data)).toList());
        if (!dead.isEmpty()) {
            tx.executeWithoutResult(status -> dead.forEach(token -> devices.findByKindAndToken("EXPO", token).ifPresent(d -> d.mark(PushDevice.Status.INVALID))));
        }
    }
}
