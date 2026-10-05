package com.bonbon.backend.notification.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * Expo Push Service over its documented HTTP API (no official Java SDK): at most 100 messages per request, urgent
 * pushes on the high-priority {@code orders_urgent} channel. A ticket answering {@code DeviceNotRegistered} marks that
 * token dead. Delivery receipts (kept 24 h) are not polled yet.
 */
public class ExpoPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushGateway.class);
    private static final int BATCH = 100;

    private final RestClient client;

    public ExpoPushGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public List<String> send(List<PushMessage> messages) {
        List<String> dead = new ArrayList<>();
        for (int from = 0; from < messages.size(); from += BATCH) {
            List<PushMessage> batch = messages.subList(from, Math.min(from + BATCH, messages.size()));
            try {
                Map<String, Object> response = client.post().body(batch.stream().map(ExpoPushGateway::payload).toList()).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() { });
                collectDead(batch, response, dead);
            } catch (RuntimeException e) {
                // A push failure never fails the business change; the stored notification is still there.
                log.warn("Expo push request failed for {} messages", batch.size(), e);
            }
        }
        return dead;
    }

    private static Map<String, Object> payload(PushMessage m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", m.token());
        body.put("title", m.title());
        body.put("body", m.body());
        body.put("data", m.data());
        body.put("sound", "default");
        body.put("priority", "high");
        body.put("channelId", "orders_urgent");
        return body;
    }

    private static void collectDead(List<PushMessage> batch, Map<String, Object> response, List<String> dead) {
        if (response == null || !(response.get("data") instanceof List<?> tickets)) {
            return;
        }
        for (int i = 0; i < tickets.size() && i < batch.size(); i++) {
            if (tickets.get(i) instanceof Map<?, ?> ticket && "error".equals(ticket.get("status"))
                    && ticket.get("details") instanceof Map<?, ?> details && "DeviceNotRegistered".equals(details.get("error"))) {
                dead.add(batch.get(i).token());
            }
        }
    }
}
