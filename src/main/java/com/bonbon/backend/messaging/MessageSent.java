package com.bonbon.backend.messaging;

import java.time.Instant;
import java.util.UUID;

/** A message was saved; {@code sender} is {@code CUSTOMER} or {@code SHOP}. Published inside the sender's transaction. */
public record MessageSent(UUID messageId, UUID conversationId, UUID customerId, UUID vendorId, String sender, Instant sentAt) {
}
