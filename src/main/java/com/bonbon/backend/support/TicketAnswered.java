package com.bonbon.backend.support;

import java.util.UUID;

/** An administrator answered a ticket. {@code audience} is CUSTOMER or SHOP: the side the person opened it from. Published inside the reply's transaction. */
public record TicketAnswered(UUID ticketId, UUID userId, String audience, String subject) {
}
