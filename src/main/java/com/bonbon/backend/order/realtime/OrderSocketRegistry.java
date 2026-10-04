package com.bonbon.backend.order.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Which open sockets listen to which channel: {@code customer:<userId>} and {@code vendor:<vendorId>}. */
@Component
class OrderSocketRegistry {

    /** An authenticated socket; it stops receiving when its access token has expired. */
    record Subscription(WebSocketSession session, Instant validUntil) {
    }

    private final Map<String, Set<Subscription>> channels = new ConcurrentHashMap<>();

    void subscribe(String channel, Subscription subscription) {
        channels.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(subscription);
    }

    void remove(WebSocketSession session) {
        channels.values().forEach(set -> set.removeIf(s -> s.session().getId().equals(session.getId())));
        channels.values().removeIf(Set::isEmpty);
    }

    List<Subscription> listeners(String channel) {
        return List.copyOf(channels.getOrDefault(channel, Set.of()));
    }
}
