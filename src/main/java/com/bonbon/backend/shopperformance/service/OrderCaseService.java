package com.bonbon.backend.shopperformance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.settlement.CaseHolds;
import com.bonbon.backend.shopperformance.OrderCaseOpened;
import com.bonbon.backend.shopperformance.dto.CaseRequests;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * A customer complaining about a delivered order (flows/order/report-order-not-received.md, report-order-incident.md).
 * Everything the refund depends on is read from the order snapshot and stored on the case when it is filed, so a later
 * commission or price change cannot alter the outcome. While the case is undecided the amount the shop would bear is
 * held out of its payable balance.
 */
@Service
public class OrderCaseService {

    static final List<String> CUSTOMER_TYPES = List.of("NOT_RECEIVED", "MISSING_ITEM", "WRONG_ITEM", "QUALITY");
    private static final long PHOTO_MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(15);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final OrderIncidents orders;
    private final CaseHolds holds;
    private final ObjectStorage storage;
    private final RateLimiter limiter;
    private final ApplicationEventPublisher events;
    private final CaseLog log;

    OrderCaseService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, OrderIncidents orders, CaseHolds holds, ObjectStorage storage,
            RateLimiter limiter, ApplicationEventPublisher events, CaseLog log) {
        this.log = log;
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.orders = orders;
        this.holds = holds;
        this.storage = storage;
        this.limiter = limiter;
        this.events = events;
    }

    // --- filing

    @Transactional
    public CaseViews.Case reportNotReceived(UUID customerId, UUID orderId, CaseRequests.NotReceived request) {
        OrderIncidents.IncidentOrder order = reportable(customerId, orderId);
        if ("CUSTOMER".equals(order.deliveredBy())) {
            throw BusinessException.conflict("ALREADY_CONFIRMED_RECEIVED", "Bạn đã xác nhận nhận được đơn này, không thể báo chưa nhận.");
        }
        List<CaseViews.Line> lines = new ArrayList<>();
        for (OrderIncidents.IncidentLine l : order.lines()) {
            lines.add(new CaseViews.Line(l.id(), l.name(), l.quantity(), refund(l, l.quantity())));
        }
        // Nothing arrived, so the delivery fee goes back too: the case refunds the whole amount charged.
        return file(order, "NOT_RECEIVED", order.grandTotal(), order.commission(), lines, List.of(), note(request == null ? null : request.note()), customerId);
    }

    @Transactional
    public CaseViews.Case reportIncident(UUID customerId, UUID orderId, CaseRequests.Incident request) {
        OrderIncidents.IncidentOrder order = reportable(customerId, orderId);
        List<CaseViews.Line> lines = claimed(order, request.lines());
        List<String> photos = request.photoKeys() == null ? List.of() : request.photoKeys();
        checkPhotos(order.id(), photos);
        if (!"MISSING_ITEM".equals(request.type()) && photos.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PHOTO_REQUIRED", "Cần ít nhất một ảnh cho loại báo cáo này.");
        }
        int refund = lines.stream().mapToInt(CaseViews.Line::refundAmount).sum();
        int commission = commissionOf(order, request.lines());
        return file(order, request.type(), refund, commission, lines, photos, note(request.note()), customerId);
    }

    /** What the customer would get back for these lines, before filing. */
    @Transactional(readOnly = true)
    public CaseViews.Quote quote(UUID customerId, UUID orderId, List<CaseRequests.Line> requested) {
        OrderIncidents.IncidentOrder order = reportable(customerId, orderId);
        List<CaseViews.Line> lines = claimed(order, requested);
        return new CaseViews.Quote(lines.stream().mapToInt(CaseViews.Line::refundAmount).sum(), lines);
    }

    @Transactional(readOnly = true)
    public CaseViews.Case caseOf(UUID customerId, UUID orderId) {
        OrderIncidents.IncidentOrder order = orders.find(orderId).filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        UUID id = jdbc.sql("select id from order_cases where order_id = :o and type in ('NOT_RECEIVED', 'MISSING_ITEM', 'WRONG_ITEM', 'QUALITY')")
                .param("o", order.id()).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Đơn này chưa có khiếu nại."));
        return view(id);
    }

    /** A photo for a complaint that is about to be filed; the key goes into {@code photoKeys}. */
    @Transactional
    public CaseViews.Upload uploadPhoto(UUID customerId, UUID orderId, MultipartFile upload) {
        OrderIncidents.IncidentOrder order = reportable(customerId, orderId);
        if (!limiter.tryAcquire("case-photo:" + order.id(), 10, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Tải ảnh quá nhiều lần, hãy thử lại sau.");
        }
        String key = storage.put(Visibility.PRIVATE, prefix(order.id()), ValidatedFile.of(upload, ValidatedFile.IMAGES, PHOTO_MAX_BYTES));
        return new CaseViews.Upload(key, storage.signedUrl(key, PHOTO_URL_TTL));
    }

    // --- internals

    private OrderIncidents.IncidentOrder reportable(UUID customerId, UUID orderId) {
        OrderIncidents.IncidentOrder order = orders.find(orderId).filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        if (!"DELIVERED".equals(order.status()) || order.deliveredAt() == null) {
            throw BusinessException.conflict("ORDER_NOT_DELIVERED", "Chỉ báo được vấn đề với đơn đã giao.");
        }
        Duration window = Duration.ofHours(settings.getLong("incident.report_window_hours", 24));
        if (!clock.instant().isBefore(order.deliveredAt().plus(window))) {
            throw BusinessException.conflict("REPORT_WINDOW_CLOSED", "Đã quá " + window.toHours() + " giờ kể từ khi đơn được giao, không báo được nữa. Hãy gửi yêu cầu hỗ trợ.");
        }
        jdbc.sql("select id from order_cases where order_id = :o and type in ('NOT_RECEIVED', 'MISSING_ITEM', 'WRONG_ITEM', 'QUALITY')").param("o", order.id())
                .query(UUID.class).optional().ifPresent(existing -> {
                    throw BusinessException.conflict("CASE_ALREADY_FILED", "Đơn này đã có khiếu nại.").withProperty("caseId", existing);
                });
        return order;
    }

    /** The claimed lines with the refund each would get; each order line may appear once, within what was ordered. */
    private List<CaseViews.Line> claimed(OrderIncidents.IncidentOrder order, List<CaseRequests.Line> requested) {
        Map<UUID, OrderIncidents.IncidentLine> byId = new LinkedHashMap<>();
        order.lines().forEach(l -> byId.put(l.id(), l));
        Set<UUID> seen = new HashSet<>();
        List<CaseViews.Line> result = new ArrayList<>();
        for (CaseRequests.Line line : requested) {
            OrderIncidents.IncidentLine ordered = byId.get(line.orderItemId());
            if (ordered == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LINE_NOT_IN_ORDER", "Có món không thuộc đơn này.").withProperty("orderItemId", line.orderItemId());
            }
            if (!seen.add(line.orderItemId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LINE_REPEATED", "Mỗi món chỉ chọn một lần.").withProperty("orderItemId", line.orderItemId());
            }
            if (line.quantity() < 1 || line.quantity() > ordered.quantity()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "QUANTITY_INVALID", "Số phần phải từ 1 đến " + ordered.quantity() + ".").withProperty("orderItemId", line.orderItemId());
            }
            result.add(new CaseViews.Line(ordered.id(), ordered.name(), line.quantity(), refund(ordered, line.quantity())));
        }
        return result;
    }

    /** {@code round((line total - its share of the discount) x claimed / ordered)}, half up. */
    static int refund(OrderIncidents.IncidentLine line, int claimed) {
        return share(line.lineTotal() - line.allocatedDiscount(), claimed, line.quantity());
    }

    static int share(int amount, int claimed, int ordered) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(claimed)).divide(BigDecimal.valueOf(ordered), 0, RoundingMode.HALF_UP).intValue();
    }

    private int commissionOf(OrderIncidents.IncidentOrder order, List<CaseRequests.Line> requested) {
        Map<UUID, OrderIncidents.IncidentLine> byId = new LinkedHashMap<>();
        order.lines().forEach(l -> byId.put(l.id(), l));
        int total = 0;
        for (CaseRequests.Line line : requested) {
            OrderIncidents.IncidentLine ordered = byId.get(line.orderItemId());
            total += share(ordered.commission(), line.quantity(), ordered.quantity());
        }
        return total;
    }

    private void checkPhotos(UUID orderId, List<String> photos) {
        int max = settings.getInt("incident.max_photos", 3);
        if (photos.size() > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_PHOTOS", "Tối đa " + max + " ảnh.");
        }
        if (new HashSet<>(photos).size() != photos.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PHOTO_INVALID", "Có ảnh bị lặp.");
        }
        for (String key : photos) {
            if (key == null || !key.startsWith(prefix(orderId) + "/")) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PHOTO_INVALID", "Ảnh không thuộc đơn này; hãy tải ảnh lên trước khi gửi.");
            }
        }
    }

    private static String prefix(UUID orderId) {
        return "order-cases/" + orderId;
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    private CaseViews.Case file(OrderIncidents.IncidentOrder order, String type, int refund, int commission, List<CaseViews.Line> lines, List<String> photos,
            String note, UUID customerId) {
        Instant now = clock.instant();
        Instant due = now.plus(Duration.ofHours(settings.getLong("incident.shop_response_hours", 12)));
        UUID id;
        try {
            id = jdbc.sql("""
                    insert into order_cases (order_id, order_number, customer_name, vendor_id, customer_id, type, status, refund_amount, commission_amount, note,
                                             shop_response_due_at, opened_by_type, opened_by_id, opened_at)
                    values (:order, :number, :name, :vendor, :customer, :type, 'AWAITING_SHOP', :refund, :commission, :note, :due, :by, :byId, :at) returning id""")
                    .param("order", order.id()).param("number", order.number()).param("name", order.customerName()).param("vendor", order.vendorId()).param("customer", customerId).param("type", type).param("refund", refund)
                    .param("commission", commission).param("note", note).param("due", Timestamp.from(due)).param("by", ActorType.CUSTOMER.name())
                    .param("byId", customerId).param("at", Timestamp.from(now)).query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("CASE_ALREADY_FILED", "Đơn này đã có khiếu nại.");
        }
        Map<UUID, OrderIncidents.IncidentLine> byId = new LinkedHashMap<>();
        order.lines().forEach(l -> byId.put(l.id(), l));
        for (CaseViews.Line line : lines) {
            OrderIncidents.IncidentLine ordered = byId.get(line.orderItemId());
            jdbc.sql("insert into order_case_items (case_id, order_item_id, item_name, quantity, refund_amount, commission_amount) values (:c, :i, :n, :q, :r, :m)")
                    .param("c", id).param("i", line.orderItemId()).param("n", line.name()).param("q", line.quantity()).param("r", line.refundAmount())
                    .param("m", share(ordered.commission(), line.quantity(), ordered.quantity())).update();
        }
        for (String key : photos) {
            jdbc.sql("insert into order_case_photos (case_id, file_key, uploaded_by_type, created_at) values (:c, :k, :by, :at)")
                    .param("c", id).param("k", key).param("by", ActorType.CUSTOMER.name()).param("at", Timestamp.from(now)).update();
        }
        log.add(id, "FILED", ActorType.CUSTOMER, customerId, type);
        orders.setIncidentHold(order.id(), true);
        holds.place(id, order.vendorId(), Math.max(refund - commission, 0));
        events.publishEvent(new OrderCaseOpened(id, order.id(), order.number(), customerId, order.vendorId(), type, refund, due));
        return view(id);
    }

    // --- reading

    /** What the customer sees of a case. */
    CaseViews.Case view(UUID caseId) {
        return load(caseId, false);
    }

    /** What the shop (and an administrator) sees: also the customer's name and what the shop would bear. */
    public CaseViews.Case viewForShop(UUID caseId) {
        return load(caseId, true);
    }

    private CaseViews.Case load(UUID caseId, boolean forShop) {
        return jdbc.sql("""
                select id, order_id, order_number, customer_name, type, status, refund_amount, commission_amount, note, shop_response_due_at, shop_response,
                       shop_response_note, opened_at, decided_at, decided_by_type, reason
                from order_cases where id = :id""").param("id", caseId)
                .query((rs, n) -> new CaseViews.Case(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("order_number"), rs.getString("type"),
                        rs.getString("status"), rs.getInt("refund_amount"), rs.getString("note"), instant(rs.getTimestamp("shop_response_due_at")),
                        rs.getString("shop_response"), rs.getString("shop_response_note"), instant(rs.getTimestamp("opened_at")),
                        instant(rs.getTimestamp("decided_at")), decidedBy(rs.getString("decided_by_type")), rs.getString("reason"), lines(caseId), photos(caseId),
                        forShop ? rs.getString("customer_name") : null, forShop ? Math.max(rs.getInt("refund_amount") - rs.getInt("commission_amount"), 0) : null))
                .single();
    }

    private List<CaseViews.Line> lines(UUID caseId) {
        return jdbc.sql("select order_item_id, item_name, quantity, refund_amount from order_case_items where case_id = :c order by item_name, id").param("c", caseId)
                .query((rs, n) -> new CaseViews.Line(rs.getObject("order_item_id", UUID.class), rs.getString("item_name"), rs.getInt("quantity"), rs.getInt("refund_amount"))).list();
    }

    private List<CaseViews.Photo> photos(UUID caseId) {
        return jdbc.sql("select file_key from order_case_photos where case_id = :c order by created_at, id").param("c", caseId)
                .query((rs, n) -> rs.getString("file_key")).list().stream().map(k -> new CaseViews.Photo(k, storage.signedUrl(k, PHOTO_URL_TTL))).toList();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String decidedBy(String type) {
        return switch (type == null ? "" : type) {
            case "" -> null;
            case "ADMIN" -> "ADMIN";
            case "CUSTOMER" -> "CUSTOMER";
            case "SYSTEM" -> "SYSTEM";
            default -> "SHOP";
        };
    }
}
