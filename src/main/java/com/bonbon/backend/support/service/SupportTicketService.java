package com.bonbon.backend.support.service;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.authentication.UserNames;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.AdminOrders;
import com.bonbon.backend.support.TicketAnswered;
import com.bonbon.backend.support.dto.TicketRequests;
import com.bonbon.backend.support.dto.TicketViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Talking to a person (flows/support/support-tickets.md). A ticket is OPEN while the person waits for an answer and ANSWERED while
 * the administrators wait for the person; an answered ticket nobody replies to is closed by the system. A person has a limited number
 * of unfinished tickets so the inbox cannot be flooded. The person never sees which administrator answered.
 */
@Service
public class SupportTicketService {

    private static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ShopOrdering shops;
    private final AdminOrders orders;
    private final UserNames userNames;
    private final ObjectStorage storage;
    private final RateLimiter limiter;
    private final ApplicationEventPublisher events;

    SupportTicketService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ShopOrdering shops, AdminOrders orders, UserNames userNames,
            ObjectStorage storage, RateLimiter limiter, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.shops = shops;
        this.orders = orders;
        this.userNames = userNames;
        this.storage = storage;
        this.limiter = limiter;
        this.events = events;
    }

    // --- the person who needs help

    @Transactional
    public TicketViews.Detail open(CurrentPrincipal caller, TicketRequests.Open request) {
        String audience = audience(caller);
        int max = settings.getInt("support.max_open_tickets", 3);
        long unfinished = jdbc.sql("select count(*) from support_tickets where user_id = :u and audience = :a and status in ('OPEN', 'ANSWERED')")
                .param("u", caller.id()).param("a", audience).query(Long.class).single();
        if (unfinished >= max) {
            throw BusinessException.conflict("TOO_MANY_OPEN_TICKETS", "Bạn đang có " + unfinished + " phiếu chưa xong; hãy đợi trả lời hoặc đóng bớt phiếu rồi gửi tiếp.");
        }
        List<String> keys = attachments(caller.id(), request.attachmentKeys());
        UUID orderId = null;
        Long orderNumber = null;
        if (request.orderId() != null) {
            AdminOrders.AdminOrder order = orders.get(request.orderId()).filter(o -> owns(caller, audience, o)).orElseThrow(
                    () -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn này trong tài khoản của bạn."));
            orderId = order.id();
            orderNumber = order.number();
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                insert into support_tickets (id, user_id, audience, order_id, order_number, subject, status, created_at, updated_at)
                values (:id, :u, :a, :o, :n, :s, 'OPEN', :now, :now)""")
                .param("id", id).param("u", caller.id()).param("a", audience).param("o", orderId).param("n", orderNumber).param("s", request.subject().strip())
                .param("now", now).update();
        addMessage(id, "USER", caller.id(), request.message().strip(), keys, now);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public TicketViews.Page list(CurrentPrincipal caller, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String audience = audience(caller);
        long total = jdbc.sql("select count(*) from support_tickets where user_id = :u and audience = :a").param("u", caller.id()).param("a", audience).query(Long.class).single();
        List<TicketViews.Summary> items = jdbc.sql("""
                select id, subject, status, order_number, created_at, updated_at from support_tickets where user_id = :u and audience = :a
                order by updated_at desc, id limit :limit offset :offset""")
                .param("u", caller.id()).param("a", audience).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new TicketViews.Summary(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("status"),
                        (Long) rs.getObject("order_number"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
        return new TicketViews.Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public TicketViews.Detail get(CurrentPrincipal caller, UUID id) {
        owned(caller, id);
        return detail(id);
    }

    /** The person writes again: an answered ticket goes back to the administrators. */
    @Transactional
    public TicketViews.Detail reply(CurrentPrincipal caller, UUID id, TicketRequests.Message request) {
        owned(caller, id);
        if (!limiter.tryAcquire("ticket-message:" + caller.id(), 30, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_MESSAGES", "Bạn gửi quá nhiều, hãy thử lại sau.");
        }
        List<String> keys = attachments(caller.id(), request.attachmentKeys());
        Timestamp now = Timestamp.from(clock.instant());
        int moved = jdbc.sql("update support_tickets set status = 'OPEN', updated_at = :now where id = :id and status in ('OPEN', 'ANSWERED')")
                .param("now", now).param("id", id).update();
        if (moved == 0) {
            throw BusinessException.conflict("TICKET_CLOSED", "Phiếu đã đóng; hãy mở phiếu mới nếu bạn còn cần hỗ trợ.");
        }
        addMessage(id, "USER", caller.id(), request.body().strip(), keys, now);
        return detail(id);
    }

    @Transactional
    public TicketViews.Detail close(CurrentPrincipal caller, UUID id) {
        owned(caller, id);
        int closed = jdbc.sql("update support_tickets set status = 'CLOSED', closed_at = :now, closed_by_type = 'USER', updated_at = :now where id = :id and status <> 'CLOSED'")
                .param("now", Timestamp.from(clock.instant())).param("id", id).update();
        if (closed == 0) {
            throw BusinessException.conflict("TICKET_CLOSED", "Phiếu đã đóng.");
        }
        return detail(id);
    }

    /** An image for a message that is about to be sent; the key goes into {@code attachmentKeys}. */
    public TicketViews.Upload upload(CurrentPrincipal caller, MultipartFile file) {
        audience(caller);
        if (!limiter.tryAcquire("ticket-attachment:" + caller.id(), 20, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Tải ảnh quá nhiều lần, hãy thử lại sau.");
        }
        String key = storage.put(Visibility.PRIVATE, prefix(caller.id()), ValidatedFile.of(file, ValidatedFile.IMAGES, IMAGE_MAX_BYTES));
        return new TicketViews.Upload(key, storage.signedUrl(key, URL_TTL));
    }

    // --- the administrators

    @Transactional(readOnly = true)
    public TicketViews.AdminPage inbox(String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (!List.of("OPEN", "ANSWERED", "CLOSED").contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` phải là OPEN, ANSWERED hoặc CLOSED.");
        }
        long total = jdbc.sql("select count(*) from support_tickets where status = :s").param("s", status).query(Long.class).single();
        record Row(UUID id, String subject, String status, UUID userId, String audience, Long orderNumber, Instant createdAt, Instant updatedAt) {
        }
        // Waiting tickets oldest first, so the one that has waited longest is on top; closed ones newest first.
        List<Row> rows = jdbc.sql("""
                select id, subject, status, user_id, audience, order_number, created_at, updated_at from support_tickets where status = :s
                order by updated_at %s, id limit :limit offset :offset""".formatted("CLOSED".equals(status) ? "desc" : "asc"))
                .param("s", status).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("subject"), rs.getString("status"), rs.getObject("user_id", UUID.class),
                        rs.getString("audience"), (Long) rs.getObject("order_number"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
        var names = userNames.names(rows.stream().map(Row::userId).distinct().toList());
        return new TicketViews.AdminPage(rows.stream().map(r -> new TicketViews.AdminRow(r.id(), r.subject(), r.status(), r.userId(), names.getOrDefault(r.userId(), "Người dùng"),
                r.audience(), r.orderNumber(), r.createdAt(), r.updatedAt())).toList(), page, size, total);
    }

    @Transactional(readOnly = true)
    public TicketViews.AdminDetail adminGet(UUID id) {
        record Head(UUID userId, String audience, UUID orderId, Long orderNumber, String subject, String status, Instant createdAt, Instant updatedAt, Instant closedAt, String closedBy) {
        }
        Head h = jdbc.sql("select user_id, audience, order_id, order_number, subject, status, created_at, updated_at, closed_at, closed_by_type from support_tickets where id = :id")
                .param("id", id)
                .query((rs, n) -> new Head(rs.getObject("user_id", UUID.class), rs.getString("audience"), rs.getObject("order_id", UUID.class), (Long) rs.getObject("order_number"),
                        rs.getString("subject"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), rs.getString("closed_by_type")))
                .optional().orElseThrow(SupportTicketService::notFound);
        List<TicketViews.AdminMessage> messages = jdbc.sql("select id, author_type, author_id, body, attachment_keys, created_at from support_ticket_messages where ticket_id = :id order by created_at, id")
                .param("id", id)
                .query((rs, n) -> new TicketViews.AdminMessage(rs.getObject("id", UUID.class), rs.getString("author_type").equals("ADMIN") ? "SUPPORT" : "USER",
                        rs.getObject("author_id", UUID.class), rs.getString("body"), attachmentViews(rs.getArray("attachment_keys")), rs.getTimestamp("created_at").toInstant()))
                .list();
        return new TicketViews.AdminDetail(id, h.subject(), h.status(), h.userId(), userNames.names(List.of(h.userId())).getOrDefault(h.userId(), "Người dùng"), h.audience(), h.orderId(),
                h.orderNumber(), h.createdAt(), h.updatedAt(), h.closedAt(), h.closedBy(), messages);
    }

    @Transactional
    public TicketViews.AdminDetail answer(CurrentPrincipal admin, UUID id, TicketRequests.Reply request) {
        Timestamp now = Timestamp.from(clock.instant());
        var ticket = jdbc.sql("select user_id, audience, subject from support_tickets where id = :id").param("id", id)
                .query((rs, n) -> new String[] { rs.getObject("user_id", UUID.class).toString(), rs.getString("audience"), rs.getString("subject") })
                .optional().orElseThrow(SupportTicketService::notFound);
        int moved = jdbc.sql("update support_tickets set status = 'ANSWERED', updated_at = :now where id = :id and status in ('OPEN', 'ANSWERED')").param("now", now).param("id", id).update();
        if (moved == 0) {
            throw BusinessException.conflict("TICKET_CLOSED", "Phiếu đã đóng; không trả lời thêm được.");
        }
        addMessage(id, "ADMIN", admin.id(), request.body().strip(), List.of(), now);
        events.publishEvent(new TicketAnswered(id, UUID.fromString(ticket[0]), ticket[1], ticket[2]));
        return adminGet(id);
    }

    /** Closes the answered tickets nobody replied to for the configured number of days; returns how many. */
    @Transactional
    public int closeStale(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(settings.getInt("support.ticket_auto_close_days", 7)));
        return jdbc.sql("""
                update support_tickets set status = 'CLOSED', closed_at = :now, closed_by_type = 'SYSTEM', updated_at = :now
                where status = 'ANSWERED' and updated_at <= :cutoff""")
                .param("now", Timestamp.from(now)).param("cutoff", Timestamp.from(cutoff)).update();
    }

    // --- internals

    private TicketViews.Detail detail(UUID id) {
        return jdbc.sql("select id, subject, status, order_id, order_number, created_at, updated_at, closed_at, closed_by_type from support_tickets where id = :id").param("id", id)
                .query((rs, n) -> new TicketViews.Detail(id, rs.getString("subject"), rs.getString("status"), rs.getObject("order_id", UUID.class), (Long) rs.getObject("order_number"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), rs.getString("closed_by_type"), messages(id)))
                .optional().orElseThrow(SupportTicketService::notFound);
    }

    private List<TicketViews.Message> messages(UUID ticketId) {
        return jdbc.sql("select id, author_type, body, attachment_keys, created_at from support_ticket_messages where ticket_id = :id order by created_at, id").param("id", ticketId)
                .query((rs, n) -> new TicketViews.Message(rs.getObject("id", UUID.class), rs.getString("author_type").equals("ADMIN") ? "SUPPORT" : "USER", rs.getString("body"),
                        attachmentViews(rs.getArray("attachment_keys")), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private List<TicketViews.Attachment> attachmentViews(Array array) throws SQLException {
        String[] keys = array == null ? new String[0] : (String[]) array.getArray();
        List<TicketViews.Attachment> views = new ArrayList<>();
        for (String key : keys) {
            views.add(new TicketViews.Attachment(key, storage.signedUrl(key, URL_TTL)));
        }
        return views;
    }

    private void addMessage(UUID ticketId, String author, UUID authorId, String body, List<String> keys, Timestamp at) {
        jdbc.sql("insert into support_ticket_messages (ticket_id, author_type, author_id, body, attachment_keys, created_at) values (:t, :a, :id, :b, :k, :at)")
                .param("t", ticketId).param("a", author).param("id", authorId).param("b", body).param("k", keys.toArray(String[]::new)).param("at", at).update();
    }

    /** The person's own ticket, or not found: another person's ticket is never confirmed to exist. */
    private void owned(CurrentPrincipal caller, UUID id) {
        long found = jdbc.sql("select count(*) from support_tickets where id = :id and user_id = :u and audience = :a").param("id", id).param("u", caller.id())
                .param("a", audience(caller)).query(Long.class).single();
        if (found == 0) {
            throw notFound();
        }
    }

    private boolean owns(CurrentPrincipal caller, String audience, AdminOrders.AdminOrder order) {
        return "CUSTOMER".equals(audience) ? order.customerId().equals(caller.id())
                : shops.operatingVendorOwnedBy(caller.id()).map(order.vendorId()::equals).orElse(false);
    }

    private List<String> attachments(UUID userId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        if (new HashSet<>(keys).size() != keys.size() || keys.stream().anyMatch(k -> !k.startsWith(prefix(userId) + "/"))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ATTACHMENT_INVALID", "Ảnh đính kèm không hợp lệ; hãy tải ảnh lên trước khi gửi.");
        }
        return List.copyOf(keys);
    }

    private static String audience(CurrentPrincipal caller) {
        return switch (caller.activeRole()) {
            case "CUSTOMER" -> "CUSTOMER";
            case "SELLER" -> "SHOP";
            default -> throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_USER", "Chỉ khách và người bán gửi phiếu hỗ trợ.");
        };
    }

    private static String prefix(UUID userId) {
        return "support/" + userId;
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("TICKET_NOT_FOUND", "Không tìm thấy phiếu hỗ trợ.");
    }
}
