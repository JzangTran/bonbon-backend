package com.bonbon.backend.messaging;

import java.util.Optional;
import java.util.UUID;

/** Whether a customer and a shop have talked, so an order lookup can link to the conversation. */
public interface ConversationLookup {

    Optional<UUID> between(UUID customerId, UUID vendorId);
}
