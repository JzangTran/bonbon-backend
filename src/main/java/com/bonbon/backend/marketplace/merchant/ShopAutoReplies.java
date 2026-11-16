package com.bonbon.backend.marketplace.merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * The message a shop sends by itself when a customer writes (configure-auto-reply.md). Messaging asks for it right after a customer's
 * message is saved and sends it once per unanswered streak.
 */
public interface ShopAutoReplies {

    /** The text to send for this shop, or empty when the shop turned it off. */
    Optional<String> autoReplyOf(UUID vendorId);
}
