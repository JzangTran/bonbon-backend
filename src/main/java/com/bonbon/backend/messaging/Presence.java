package com.bonbon.backend.messaging;

import java.util.UUID;

/**
 * Whether the other side of a chat has the app open right now (a live socket). Implemented where the sockets live; the
 * notification module asks it to decide between "they already saw it" and "send a push".
 */
public interface Presence {

    boolean customerOnline(UUID customerId);

    boolean shopOnline(UUID vendorId);
}
