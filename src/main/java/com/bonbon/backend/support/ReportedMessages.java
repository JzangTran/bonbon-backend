package com.bonbon.backend.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What reporting needs from chat, implemented by messaging (communication depends on support, so the dependency points this way, like
 * {@link ConversationLookup}): the reported message with the messages around it as evidence, hiding it, and stopping its sender from
 * writing for a while.
 */
public interface ReportedMessages {

    /** One message as it was when reported; {@code imageKey} is the private storage key, never a link. */
    record MessageSnapshot(UUID id, String senderType, UUID senderId, String text, String imageKey, Instant createdAt) {
    }

    /** The reported message, who the two sides are, and up to {@code context} messages before and after it. */
    record Evidence(UUID conversationId, UUID customerId, UUID vendorId, MessageSnapshot message, List<MessageSnapshot> before,
            List<MessageSnapshot> after) {
    }

    Optional<Evidence> evidence(UUID messageId, int context);

    /** Shown to the other side as removed for breaking the rules; the text is kept for administrators. */
    void hide(UUID messageId, String reason);

    /** The person cannot send messages until {@code until}; a longer restriction already in place is kept. */
    void restrict(UUID userId, Instant until, String reason, UUID reportId, UUID adminId);

    /** Undoes {@link #hide} after a contested decision is overturned. */
    void unhide(UUID messageId);

    /** Lifts the restriction placed for this report, if it is still the one in force. */
    void liftRestriction(UUID userId, UUID reportId);
}
