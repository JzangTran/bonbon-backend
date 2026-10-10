package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.shopperformance.NoShowReported;
import com.bonbon.backend.shopperformance.OrderCaseEscalated;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * A shop saying the customer was not there when the food arrived, and what follows (flows/order-fulfillment/
 * report-customer-no-show.md, flows/order/answer-no-show-report.md). Only the shop knows, so filing is opt-in: a shop that
 * never files gets the usual "delivered after 3 hours". While a case is open the order is held out of that timer. The
 * customer answers, an administrator decides when they disagree, and the order then ends as delivered, not delivered or
 * cancelled. No money is claimed either way: a refund only follows when the shop never came and the order was paid online.
 */
@Service
public class NoShowService {

    private static final long PHOTO_MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration PHOTO_URL_TTL = Duration.ofMinutes(15);
    private static final Set<String> ANSWERS = Set.of("UNABLE", "RECEIVED", "SHOP_NEVER_CAME");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ShopOrdering shops;
    private final OrderIncidents orders;
    private final OrderCaseService cases;
    private final OrderCaseDecisionService decisions;
    private final ObjectStorage storage;
    private final RateLimiter limiter;
    private final CaseLog log;
    private final ApplicationEventPublisher events;

    NoShowService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ShopOrdering shops, OrderIncidents orders, OrderCaseService cases,
            OrderCaseDecisionService decisions, ObjectStorage storage, RateLimiter limiter, CaseLog log, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.shops = shops;
        this.orders = orders;
        this.cases = cases;
        this.decisions = decisions;
        this.storage = storage;
        this.limiter = limiter;
        this.log = log;
        this.events = events;
    }

    // --- the shop files

    @Transactional
    public CaseViews.Case file(CurrentPrincipal caller, UUID orderId, String note, String photoKey) {
        OrderIncidents.IncidentOrder order = ownOrder(caller, orderId);
        if (!"OUT_FOR_DELIVERY".equals(order.status())) {
            throw BusinessException.conflict("ORDER_NOT_OUT_FOR_DELIVERY", "Chỉ báo khách vắng mặt khi đơn đang giao.").withProperty("status", order.status());
        }
        Instant now = clock.instant();
        Instant earliest = order.outForDeliveryAt() == null ? now : order.outForDeliveryAt().plus(Duration.ofMinutes(settings.getLong("incident.no_show_min_wait_minutes", 10)));
        if (now.isBefore(earliest)) {
            throw BusinessException.conflict("NO_SHOW_TOO_EARLY", "Hãy chờ khách thêm một lúc rồi hãy báo.").withProperty("availableAt", earliest);
        }
        if (photoKey != null && !photoKey.startsWith(prefix(orderId) + "/")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PHOTO_INVALID", "Ảnh không thuộc đơn này; hãy tải ảnh lên trước khi gửi.");
        }
        Instant due = now.plus(Duration.ofHours(settings.getLong("incident.customer_response_hours", 2)));
        UUID id;
        try {
            id = jdbc.sql("""
                    insert into order_cases (order_id, order_number, customer_name, vendor_id, customer_id, type, status, note, customer_answer_due_at,
                                             opened_by_type, opened_by_id, opened_at)
                    values (:order, :number, :name, :vendor, :customer, 'CUSTOMER_NO_SHOW', 'AWAITING_CUSTOMER', :note, :due, :by, :byId, :at) returning id""")
                    .param("order", order.id()).param("number", order.number()).param("name", order.customerName()).param("vendor", order.vendorId())
                    .param("customer", order.customerId()).param("note", note.strip()).param("due", Timestamp.from(due)).param("by", caller.actorType().name())
                    .param("byId", caller.id()).param("at", Timestamp.from(now)).query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("CASE_ALREADY_FILED", "Đơn này đã có báo cáo khách vắng mặt.");
        }
        if (!orders.holdIfOutForDelivery(orderId)) {
            // The customer pressed "received" in between (or a case already holds the order): nothing is left half done.
            throw BusinessException.conflict("ORDER_ALREADY_CHANGED", "Đơn hàng vừa được cập nhật. Hãy tải lại để xem trạng thái mới.");
        }
        if (photoKey != null) {
            jdbc.sql("insert into order_case_photos (case_id, file_key, uploaded_by_type, created_at) values (:c, :k, :by, :at)")
                    .param("c", id).param("k", photoKey).param("by", caller.actorType().name()).param("at", Timestamp.from(now)).update();
        }
        log.add(id, "FILED", caller.actorType(), caller.id(), "CUSTOMER_NO_SHOW");
        events.publishEvent(new NoShowReported(id, orderId, order.number(), order.customerId(), order.vendorId(), due));
        return cases.viewForShop(id);
    }

    /** A photo of the door or the call log, uploaded before the report is sent; its key goes in {@code photoKey}. */
    @Transactional
    public CaseViews.Upload uploadPhoto(CurrentPrincipal caller, UUID orderId, MultipartFile upload) {
        OrderIncidents.IncidentOrder order = ownOrder(caller, orderId);
        if (!"OUT_FOR_DELIVERY".equals(order.status())) {
            throw BusinessException.conflict("ORDER_NOT_OUT_FOR_DELIVERY", "Chỉ báo khách vắng mặt khi đơn đang giao.").withProperty("status", order.status());
        }
        if (!limiter.tryAcquire("no-show-photo:" + order.id(), 5, Duration.ofHours(1))) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_UPLOADS", "Tải ảnh quá nhiều lần, hãy thử lại sau.");
        }
        String key = storage.put(Visibility.PRIVATE, prefix(order.id()), ValidatedFile.of(upload, ValidatedFile.IMAGES, PHOTO_MAX_BYTES));
        return new CaseViews.Upload(key, storage.signedUrl(key, PHOTO_URL_TTL));
    }

    // --- the customer answers

    @Transactional(readOnly = true)
    public CaseViews.Case caseOf(UUID customerId, UUID orderId) {
        UUID id = jdbc.sql("select id from order_cases where order_id = :o and customer_id = :c and type = 'CUSTOMER_NO_SHOW'").param("o", orderId).param("c", customerId)
                .query(UUID.class).optional().orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Đơn này chưa có báo cáo khách vắng mặt."));
        return cases.view(id);
    }

    @Transactional
    public CaseViews.Case answer(UUID customerId, UUID orderId, String answer, String note) {
        if (!ANSWERS.contains(answer)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ANSWER", "Câu trả lời phải là UNABLE, RECEIVED hoặc SHOP_NEVER_CAME.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if ("SHOP_NEVER_CAME".equals(answer) && cleanNote == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", "Hãy cho biết quán đã không đến hay không gọi như thế nào.");
        }
        UUID id = jdbc.sql("select id from order_cases where order_id = :o and customer_id = :c and type = 'CUSTOMER_NO_SHOW'").param("o", orderId).param("c", customerId)
                .query(UUID.class).optional().orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Đơn này chưa có báo cáo khách vắng mặt."));
        int changed = jdbc.sql("""
                update order_cases set customer_answer = :answer, customer_answer_note = :note, customer_answered_at = :at, version = version + 1
                where id = :id and status = 'AWAITING_CUSTOMER'""")
                .param("answer", answer).param("note", cleanNote).param("at", Timestamp.from(clock.instant())).param("id", id).update();
        if (changed == 0) {
            throw BusinessException.conflict("CASE_ALREADY_ANSWERED", "Báo cáo này không còn chờ bạn trả lời.");
        }
        log.add(id, "CUSTOMER_" + answer, ActorType.CUSTOMER, customerId, cleanNote);
        switch (answer) {
            case "UNABLE" -> decisions.decideNoShow(id, List.of("AWAITING_CUSTOMER"), "CUSTOMER_AT_FAULT", ActorType.CUSTOMER, customerId, "Khách không thể nhận hoặc không muốn nhận đơn");
            case "RECEIVED" -> decisions.decideNoShow(id, List.of("AWAITING_CUSTOMER"), "CUSTOMER_RECEIVED", ActorType.CUSTOMER, customerId, "Khách xác nhận đã nhận được đơn");
            default -> {
                jdbc.sql("update order_cases set status = 'OPEN', version = version + 1 where id = :id and status = 'AWAITING_CUSTOMER'").param("id", id).update();
                escalated(id, "SHOP_NEVER_CAME");
            }
        }
        return cases.view(id);
    }

    /** The order was marked delivered by someone while a no-show case waits: the case is over, the customer did receive it. */
    @EventListener
    void onOrderStatusChanged(OrderStatusChanged event) {
        if (event.to() != com.bonbon.backend.order.OrderStatus.DELIVERED) {
            return;
        }
        jdbc.sql("select id from order_cases where order_id = :o and type = 'CUSTOMER_NO_SHOW' and status in ('AWAITING_CUSTOMER', 'OPEN')").param("o", event.orderId())
                .query(UUID.class).list().forEach(id -> decisions.decideNoShowWithoutMovingOrder(id, "CUSTOMER_RECEIVED", ActorType.SYSTEM, null, "Đơn đã được xác nhận giao"));
    }

    /** Hands every no-show whose customer did not answer in time to an administrator. Silence is not an admission. */
    @Transactional
    public int escalateNoReply(Instant now) {
        List<UUID> overdue = jdbc.sql("update order_cases set status = 'OPEN', version = version + 1 where status = 'AWAITING_CUSTOMER' and customer_answer_due_at <= :now returning id")
                .param("now", Timestamp.from(now)).query(UUID.class).list();
        overdue.forEach(id -> {
            log.add(id, "NO_REPLY", ActorType.SYSTEM, null, null);
            escalated(id, "CUSTOMER_NO_REPLY");
        });
        return overdue.size();
    }

    // --- internals

    private OrderIncidents.IncidentOrder ownOrder(CurrentPrincipal caller, UUID orderId) {
        UUID vendor = shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt."));
        return orders.find(orderId).filter(o -> o.vendorId().equals(vendor)).orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
    }

    private void escalated(UUID caseId, String why) {
        jdbc.sql("select order_id, order_number, customer_id, vendor_id from order_cases where id = :id").param("id", caseId)
                .query((rs, n) -> new OrderCaseEscalated(caseId, rs.getObject("order_id", UUID.class), rs.getLong("order_number"), rs.getObject("customer_id", UUID.class),
                        rs.getObject("vendor_id", UUID.class), why))
                .optional().ifPresent(events::publishEvent);
    }

    private static String prefix(UUID orderId) {
        return "order-cases/" + orderId;
    }
}
