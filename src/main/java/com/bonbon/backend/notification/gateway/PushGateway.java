package com.bonbon.backend.notification.gateway;

import java.util.List;
import java.util.Map;

/**
 * Sends pushes to phones. The only implementation that talks to the outside is Expo Push Service; behind this
 * interface a switch to FCM or APNs directly stays a backend-internal change (push-notifications.md).
 */
public interface PushGateway {

    /** {@code data} carries ids only: lock-screen text is visible to people other than the account holder. */
    record PushMessage(String token, String title, String body, Map<String, String> data) {
    }

    /** Sends the messages and returns the tokens the provider reports as dead, so the caller can retire them. */
    List<String> send(List<PushMessage> messages);
}
