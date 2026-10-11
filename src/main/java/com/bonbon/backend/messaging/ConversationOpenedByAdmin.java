package com.bonbon.backend.messaging;

import java.time.Instant;
import java.util.UUID;

/** An administrator opened a conversation's messages (admin-conversation:read); published so the access is audited. */
public record ConversationOpenedByAdmin(UUID adminId, UUID conversationId, UUID customerId, UUID vendorId, Instant at) {
}
