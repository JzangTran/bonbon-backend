package com.bonbon.backend.notification.service;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.authentication.CustomerDirectory;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.messaging.MessageSent;
import com.bonbon.backend.messaging.Presence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells someone about a chat message they are not looking at (push-notifications.md, "Chat messages"): only when they have no
 * open socket, only on the channels they left on, and at most once a minute (push) or a quarter of an hour (email) per
 * conversation, so a burst of messages is one nudge. Neither carries the text: a lock screen is visible to other people.
 * A chat message is not a stored notification; the conversation list has its own unread mark.
 */
@Component
class ChatNotifier {

    private static final Logger log = LoggerFactory.getLogger(ChatNotifier.class);

    private final Presence presence;
    private final ShopOrdering shops;
    private final PreferenceService preferences;
    private final PushDispatcher push;
    private final CustomerDirectory accounts;
    private final EmailSender email;
    private final RateLimiter limiter;

    ChatNotifier(Presence presence, ShopOrdering shops, PreferenceService preferences, PushDispatcher push, CustomerDirectory accounts, EmailSender email,
            RateLimiter limiter) {
        this.presence = presence;
        this.shops = shops;
        this.preferences = preferences;
        this.push = push;
        this.accounts = accounts;
        this.email = email;
        this.limiter = limiter;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessage(MessageSent event) {
        try {
            boolean toShop = "CUSTOMER".equals(event.sender());
            if (toShop ? presence.shopOnline(event.vendorId()) : presence.customerOnline(event.customerId())) {
                return;
            }
            Optional<UUID> recipient = toShop ? shops.ownerOf(event.vendorId()) : Optional.of(event.customerId());
            recipient.ifPresent(user -> notify(user, event, toShop));
        } catch (RuntimeException e) {
            log.warn("Chat notification failed", e);
        }
    }

    private void notify(UUID user, MessageSent event, boolean toShop) {
        String from = toShop ? "khách" : "cửa hàng";
        if (preferences.allows(user, NotificationCategory.CHAT_MESSAGES, "PUSH") && limiter.tryAcquire("chat-push:" + user + ":" + event.conversationId(), 1, Duration.ofMinutes(1))) {
            push.sendTo(user, "Tin nhắn mới", "Bạn có tin nhắn mới từ " + from + ".",
                    Map.of("type", "CHAT_MESSAGE", "conversationId", event.conversationId().toString(), "audience", toShop ? "SHOP" : "CUSTOMER"));
        }
        if (preferences.allows(user, NotificationCategory.CHAT_MESSAGES, "EMAIL") && limiter.tryAcquire("chat-email:" + user + ":" + event.conversationId(), 1, Duration.ofMinutes(15))) {
            accounts.get(user).filter(CustomerDirectory.Account::emailVerified).ifPresent(a -> email.send(new EmailSender.EmailMessage(a.email(),
                    "Bạn có tin nhắn mới trên Bonbon", "Bạn có tin nhắn mới từ " + from + ". Mở ứng dụng Bonbon để xem và trả lời.")));
        }
    }
}
