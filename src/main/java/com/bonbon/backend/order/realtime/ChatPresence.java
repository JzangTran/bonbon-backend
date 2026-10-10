package com.bonbon.backend.order.realtime;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.messaging.Presence;
import org.springframework.stereotype.Component;

/** A side is online when at least one of its authenticated sockets is open and its token has not expired. */
@Component
class ChatPresence implements Presence {

    private final OrderSocketRegistry registry;

    ChatPresence(OrderSocketRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean customerOnline(UUID customerId) {
        return online(OrderSocketHandler.customerChannel(customerId));
    }

    @Override
    public boolean shopOnline(UUID vendorId) {
        return online(OrderSocketHandler.vendorChannel(vendorId));
    }

    private boolean online(String channel) {
        Instant now = Instant.now();
        return registry.listeners(channel).stream().anyMatch(s -> s.session().isOpen() && now.isBefore(s.validUntil()));
    }
}
