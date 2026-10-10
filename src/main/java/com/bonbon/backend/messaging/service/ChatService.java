package com.bonbon.backend.messaging.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.authentication.UserNames;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.merchant.ShopNames;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.messaging.MessageSent;
import com.bonbon.backend.messaging.dto.ChatRequests;
import com.bonbon.backend.messaging.dto.ChatViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Chat between a customer and a shop (flows/messaging/send-message.md, view-conversation-list.md, view-conversation-history.md).
 * A caller is on one side: a customer, or the shop they own; every read and write is scoped to that side, so another
 * conversation is simply not found. The customer only ever sees "the shop", never a person on its side.
 */
@Service
public class ChatService {

    static final int MAX_TEXT = 1000;
    private static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration IMAGE_URL_TTL = Duration.ofMinutes(15);
    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ShopOrdering shops;
    private final ShopNames shopNames;
    private final UserNames userNames;
    private final ObjectStorage storage;
    private final RateLimiter limiter;
    private final ApplicationEventPublisher events;

    ChatService(JdbcClient jdbc, Clock clock, ShopOrdering shops, ShopNames shopNames, UserNames userNames, ObjectStorage storage, RateLimiter limiter,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.shops = shops;
        this.shopNames = shopNames;
        this.userNames = userNames;
        this.storage = storage;
        this.limiter = limiter;
        this.events = events;
    }

    /** Which side of the conversations the caller speaks for: {@code id} is the customer's user id or the shop's vendor id. */
    private record Side(boolean shop, UUID id) {

        String name() {
            return shop ? "SHOP" : "CUSTOMER";
        }

        String other() {
            return shop ? "CUSTOMER" : "SHOP";
        }

        String column() {
            return shop ? "vendor_id" : "customer_id";
        }

        String readColumn() {
            return shop ? "shop_last_read_at" : "customer_last_read_at";
        }
    }

    private Side side(CurrentPrincipal caller) {
        return switch (caller.activeRole()) {
            case "CUSTOMER" -> new Side(false, caller.id());
            case "SELLER" -> new Side(true, shops.operatingVendorOwnedBy(caller.id())
                    .orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt.")));
            default -> throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_PARTICIPANT", "Chỉ khách và cửa hàng mới trò chuyện được.");
        };
    }

    // --- conversations

    @Transactional(readOnly = true)
    public ChatViews.Page list(CurrentPrincipal caller, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        Side side = side(caller);
        String unread = unreadSql(side);
        long total = jdbc.sql("select count(*) from conversations c where c." + side.column() + " = :me").param("me", side.id()).query(Long.class).single();
        long unreadCount = jdbc.sql("select count(*) from conversations c where c." + side.column() + " = :me and " + unread)
                .param("me", side.id()).query(Long.class).single();
        record Row(UUID id, UUID customerId, UUID vendorId, String text, boolean hasImage, String sender, Instant sentAt, boolean unread) {
        }
        List<Row> rows = jdbc.sql("""
                select c.id, c.customer_id, c.vendor_id, lm.text, lm.image_key is not null as has_image, lm.sender_type, lm.created_at, %s as unread
                from conversations c
                join lateral (select text, image_key, sender_type, created_at from messages m where m.conversation_id = c.id
                              order by m.created_at desc, m.id desc limit 1) lm on true
                where c.%s = :me
                order by c.last_message_at desc, c.id
                limit :limit offset :offset""".formatted(unread, side.column()))
                .param("me", side.id()).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class),
                        rs.getString("text"), rs.getBoolean("has_image"), rs.getString("sender_type"), rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("unread")))
                .list();
        Map<UUID, String> shopLabels = shopNames.names(rows.stream().map(Row::vendorId).distinct().toList());
        Map<UUID, String> customerLabels = userNames.names(rows.stream().map(Row::customerId).distinct().toList());
        List<ChatViews.Conversation> items = rows.stream()
                .map(r -> new ChatViews.Conversation(r.id(), r.vendorId(), shopLabels.getOrDefault(r.vendorId(), "Cửa hàng"),
                        customerLabels.getOrDefault(r.customerId(), "Khách"), new ChatViews.LastMessage(r.text(), r.hasImage(), r.sender(), r.sentAt()), r.unread()))
                .toList();
        return new ChatViews.Page(items, page, size, total, unreadCount);
    }

    /** The conversation between the calling customer and this shop, so the app can open it from the shop's screen. */
    @Transactional(readOnly = true)
    public ChatViews.Conversation withShop(CurrentPrincipal caller, UUID vendorId) {
        Side side = side(caller);
        if (side.shop()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_PARTICIPANT", "Chỉ khách mới mở cuộc trò chuyện với một cửa hàng.");
        }
        UUID id = jdbc.sql("select id from conversations where customer_id = :c and vendor_id = :v").param("c", side.id()).param("v", vendorId)
                .query(UUID.class).optional()
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Bạn chưa nhắn tin với cửa hàng này."));
        return summary(side, id);
    }

    @Transactional(readOnly = true)
    public ChatViews.Messages messages(CurrentPrincipal caller, UUID conversationId, Instant before, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Kích thước trang không hợp lệ.");
        }
        Side side = side(caller);
        conversationOf(side, conversationId);
        List<ChatViews.Message> found = jdbc.sql(MESSAGE_SELECT + """
                where m.conversation_id = :c and (cast(:before as timestamptz) is null or m.created_at < :before)
                order by m.created_at desc, m.id desc limit :limit""")
                .param("c", conversationId).param("before", before == null ? null : Timestamp.from(before)).param("limit", size + 1)
                .query((rs, n) -> toMessage(rs, side)).list();
        boolean more = found.size() > size;
        List<ChatViews.Message> items = more ? new ArrayList<>(found.subList(0, size)) : found;
        return new ChatViews.Messages(items, more, more ? items.get(items.size() - 1).createdAt() : null);
    }

    @Transactional
    public void markRead(CurrentPrincipal caller, UUID conversationId) {
        Side side = side(caller);
        conversationOf(side, conversationId);
        jdbc.sql("update conversations set " + side.readColumn() + " = :now where id = :id").param("now", Timestamp.from(clock.instant()))
                .param("id", conversationId).update();
    }

    // --- sending

    /** A customer writes to a shop; the first message creates the conversation. */
    @Transactional
    public ChatViews.Message sendToShop(CurrentPrincipal caller, UUID vendorId, ChatRequests.Send request) {
        Side side = side(caller);
        if (side.shop()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_PARTICIPANT", "Cửa hàng trả lời trong cuộc trò chuyện sẵn có.");
        }
        if (shops.shop(vendorId).isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "Không tìm thấy cửa hàng.");
        }
        Instant now = clock.instant();
        jdbc.sql("""
                insert into conversations (id, customer_id, vendor_id, created_at, last_message_at) values (:id, :c, :v, :now, :now)
                on conflict (customer_id, vendor_id) do nothing""")
                .param("id", UUID.randomUUID()).param("c", side.id()).param("v", vendorId).param("now", Timestamp.from(now)).update();
        UUID conversationId = jdbc.sql("select id from conversations where customer_id = :c and vendor_id = :v").param("c", side.id()).param("v", vendorId)
                .query(UUID.class).single();
        return post(side, caller.id(), conversationId, request);
    }

    /** Either side writes into a conversation it already has. */
    @Transactional
    public ChatViews.Message send(CurrentPrincipal caller, UUID conversationId, ChatRequests.Send request) {
        Side side = side(caller);
        conversationOf(side, conversationId);
        return post(side, caller.id(), conversationId, request);
    }

    /** An image for a message that is about to be sent; the key goes into {@code imageKey}. */
    public ChatViews.Upload uploadImage(CurrentPrincipal caller, MultipartFile upload) {
        side(caller);
        if (!limiter.tryAcquire("chat-image:" + caller.id(), 30, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Tải ảnh quá nhiều lần, hãy thử lại sau.");
        }
        String key = storage.put(Visibility.PRIVATE, prefix(caller.id()), ValidatedFile.of(upload, ValidatedFile.IMAGES, IMAGE_MAX_BYTES));
        return new ChatViews.Upload(key, storage.signedUrl(key, IMAGE_URL_TTL));
    }

    // --- internals

    private ChatViews.Message post(Side side, UUID senderId, UUID conversationId, ChatRequests.Send request) {
        String text = request == null || request.text() == null || request.text().isBlank() ? null : request.text().strip();
        String image = request == null || request.imageKey() == null || request.imageKey().isBlank() ? null : request.imageKey();
        if (text == null && image == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_EMPTY", "Tin nhắn cần có chữ hoặc ảnh.");
        }
        if (text != null && text.length() > MAX_TEXT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_TOO_LONG", "Tin nhắn tối đa " + MAX_TEXT + " ký tự.");
        }
        if (image != null && !image.startsWith(prefix(senderId) + "/")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID", "Ảnh không hợp lệ; hãy tải ảnh lên trước khi gửi.");
        }
        UUID replyTo = request == null ? null : request.replyToMessageId();
        if (replyTo != null && jdbc.sql("select count(*) from messages where id = :m and conversation_id = :c").param("m", replyTo).param("c", conversationId)
                .query(Long.class).single() == 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REPLY_NOT_FOUND", "Tin nhắn được trả lời không thuộc cuộc trò chuyện này.");
        }
        UUID customerId = jdbc.sql("select customer_id from conversations where id = :id").param("id", conversationId).query(UUID.class).single();
        UUID vendorId = jdbc.sql("select vendor_id from conversations where id = :id").param("id", conversationId).query(UUID.class).single();
        if (!side.shop() && shops.shop(vendorId).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_UNAVAILABLE", "Cửa hàng hiện không nhận tin nhắn.");
        }
        if (!limiter.tryAcquire("chat-send:" + senderId, 30, Duration.ofMinutes(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_MESSAGES", "Bạn gửi quá nhanh, hãy thử lại sau ít phút.");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                insert into messages (id, conversation_id, sender_type, sender_id, text, image_key, reply_to_message_id, created_at)
                values (:id, :c, :type, :sender, :text, :image, :reply, :now)""")
                .param("id", id).param("c", conversationId).param("type", side.name()).param("sender", senderId).param("text", text)
                .param("image", image).param("reply", replyTo).param("now", now).update();
        jdbc.sql("update conversations set last_message_at = :now, " + side.readColumn() + " = :now where id = :id").param("now", now)
                .param("id", conversationId).update();
        events.publishEvent(new MessageSent(id, conversationId, customerId, vendorId, side.name(), now.toInstant()));
        return jdbc.sql(MESSAGE_SELECT + "where m.id = :id").param("id", id).query((rs, n) -> toMessage(rs, side)).single();
    }

    private void conversationOf(Side side, UUID conversationId) {
        long found = jdbc.sql("select count(*) from conversations where id = :id and " + side.column() + " = :me").param("id", conversationId)
                .param("me", side.id()).query(Long.class).single();
        if (found == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Không tìm thấy cuộc trò chuyện.");
        }
    }

    private ChatViews.Conversation summary(Side side, UUID conversationId) {
        String unread = unreadSql(side);
        record Row(UUID customerId, UUID vendorId, String text, boolean hasImage, String sender, Instant sentAt, boolean unread) {
        }
        Row r = jdbc.sql("""
                select c.customer_id, c.vendor_id, lm.text, lm.image_key is not null as has_image, lm.sender_type, lm.created_at, %s as unread
                from conversations c
                join lateral (select text, image_key, sender_type, created_at from messages m where m.conversation_id = c.id
                              order by m.created_at desc, m.id desc limit 1) lm on true
                where c.id = :id""".formatted(unread)).param("id", conversationId)
                .query((rs, n) -> new Row(rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("text"),
                        rs.getBoolean("has_image"), rs.getString("sender_type"), rs.getTimestamp("created_at").toInstant(), rs.getBoolean("unread")))
                .single();
        String shop = shopNames.names(List.of(r.vendorId())).getOrDefault(r.vendorId(), "Cửa hàng");
        String customer = userNames.names(List.of(r.customerId())).getOrDefault(r.customerId(), "Khách");
        return new ChatViews.Conversation(conversationId, r.vendorId(), shop, customer,
                new ChatViews.LastMessage(r.text(), r.hasImage(), r.sender(), r.sentAt()), r.unread());
    }

    private static String unreadSql(Side side) {
        return "exists (select 1 from messages u where u.conversation_id = c.id and u.sender_type = '" + side.other() + "' and u.created_at > coalesce(c."
                + side.readColumn() + ", cast('1970-01-01' as timestamptz)))";
    }

    private static final String MESSAGE_SELECT = """
            select m.id, m.conversation_id, m.sender_type, m.text, m.image_key, m.created_at,
                   r.id as reply_id, r.text as reply_text, r.image_key is not null as reply_has_image, r.sender_type as reply_sender
            from messages m left join messages r on r.id = m.reply_to_message_id
            """;

    private ChatViews.Message toMessage(java.sql.ResultSet rs, Side side) throws java.sql.SQLException {
        String image = rs.getString("image_key");
        ChatViews.Reply reply = rs.getObject("reply_id", UUID.class) == null ? null
                : new ChatViews.Reply(rs.getObject("reply_id", UUID.class), rs.getString("reply_text"), rs.getBoolean("reply_has_image"), rs.getString("reply_sender"));
        String sender = rs.getString("sender_type");
        return new ChatViews.Message(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class), sender, sender.equals(side.name()),
                rs.getString("text"), image == null ? null : storage.signedUrl(image, IMAGE_URL_TTL), reply, rs.getTimestamp("created_at").toInstant());
    }

    private static String prefix(UUID userId) {
        return "chat/" + userId;
    }
}
