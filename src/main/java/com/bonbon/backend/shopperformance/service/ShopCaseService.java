package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.shopperformance.OrderCaseEscalated;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop answering the cases customers filed about its orders (flows/order-fulfillment/respond-to-order-case.md).
 * Accepting settles the case at once; disputing, or staying silent past the deadline, hands it to an administrator.
 * Every call is scoped to the caller's own shop: another shop's case is simply not found.
 */
@Service
public class ShopCaseService {

    private static final Set<String> STATUSES = Set.of("AWAITING_SHOP", "AWAITING_CUSTOMER", "OPEN", "UPHELD", "DISMISSED");
    private static final int MAX_PAGE_SIZE = 50;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ShopOrdering shops;
    private final OrderCaseService cases;
    private final OrderCaseDecisionService decisions;
    private final ApplicationEventPublisher events;
    private final CaseLog log;
    private final NoShowService noShows;

    ShopCaseService(JdbcClient jdbc, Clock clock, ShopOrdering shops, OrderCaseService cases, OrderCaseDecisionService decisions, ApplicationEventPublisher events, CaseLog log, NoShowService noShows) {
        this.log = log;
        this.noShows = noShows;
        this.jdbc = jdbc;
        this.clock = clock;
        this.shops = shops;
        this.cases = cases;
        this.decisions = decisions;
        this.events = events;
    }

    /** The cases waiting for this shop come first, then the ones with an administrator, then the settled ones. */
    @Transactional(readOnly = true)
    public CaseViews.Page list(CurrentPrincipal caller, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` không hợp lệ.");
        }
        UUID vendor = vendorOf(caller);
        long total = jdbc.sql("select count(*) from order_cases where vendor_id = :v and (cast(:s as text) is null or status = :s)")
                .param("v", vendor).param("s", status).query(Long.class).single();
        List<CaseViews.Summary> items = jdbc.sql("""
                select id, order_id, order_number, type, status, refund_amount, commission_amount, shop_response_due_at, opened_at
                from order_cases where vendor_id = :v and (cast(:s as text) is null or status = :s)
                order by case status when 'AWAITING_SHOP' then 0 when 'AWAITING_CUSTOMER' then 1 when 'OPEN' then 2 else 3 end, opened_at desc, id
                limit :limit offset :offset""")
                .param("v", vendor).param("s", status).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new CaseViews.Summary(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getString("type"), rs.getString("status"), rs.getInt("refund_amount"),
                        Math.max(rs.getInt("refund_amount") - rs.getInt("commission_amount"), 0),
                        rs.getTimestamp("shop_response_due_at") == null ? null : rs.getTimestamp("shop_response_due_at").toInstant(),
                        rs.getTimestamp("opened_at").toInstant()))
                .list();
        return new CaseViews.Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public CaseViews.Case get(CurrentPrincipal caller, UUID caseId) {
        owned(vendorOf(caller), caseId);
        return cases.viewForShop(caseId);
    }

    /** The shop agrees: the customer is refunded and the shop bears it. */
    @Transactional
    public CaseViews.Case accept(CurrentPrincipal caller, UUID caseId) {
        UUID vendor = vendorOf(caller);
        answer(vendor, caseId, "ACCEPTED", null, "AWAITING_SHOP");
        log.add(caseId, "SHOP_ACCEPTED", caller.actorType(), caller.id(), null);
        decisions.decide(caseId, List.of("AWAITING_SHOP"), true, caller.actorType(), caller.id(), "Quán đã chấp nhận khiếu nại");
        return cases.viewForShop(caseId);
    }

    /** The shop disagrees: an administrator decides, with the shop's note in front of them. */
    @Transactional
    public CaseViews.Case dispute(CurrentPrincipal caller, UUID caseId, String note) {
        UUID vendor = vendorOf(caller);
        answer(vendor, caseId, "DISPUTED", note.strip(), "OPEN");
        log.add(caseId, "SHOP_DISPUTED", caller.actorType(), caller.id(), note.strip());
        escalated(caseId, "DISPUTED");
        return cases.viewForShop(caseId);
    }

    /** Hands every case whose time ran out to an administrator. Never decides one. Safe to run twice. */
    @Transactional
    public int escalateOverdue(Instant now) {
        List<UUID> overdue = jdbc.sql("update order_cases set status = 'OPEN', version = version + 1 where status = 'AWAITING_SHOP' and shop_response_due_at <= :now returning id")
                .param("now", Timestamp.from(now)).query(UUID.class).list();
        overdue.forEach(id -> {
            log.add(id, "NO_RESPONSE", com.bonbon.backend.common.persistence.ActorType.SYSTEM, null, null);
            escalated(id, "NO_RESPONSE");
        });
        return overdue.size() + noShows.escalateNoReply(now);
    }

    // --- internals

    private void answer(UUID vendorId, UUID caseId, String response, String note, String nextStatus) {
        int changed = jdbc.sql("""
                update order_cases set shop_response = :response, shop_response_note = :note, shop_responded_at = :at, status = :next, version = version + 1
                where id = :id and vendor_id = :v and status = 'AWAITING_SHOP'""")
                .param("response", response).param("note", note).param("at", Timestamp.from(clock.instant())).param("next", nextStatus)
                .param("id", caseId).param("v", vendorId).update();
        if (changed == 0) {
            owned(vendorId, caseId);
            throw BusinessException.conflict("CASE_ALREADY_ANSWERED", "Khiếu nại này không còn chờ quán trả lời.");
        }
    }

    private void escalated(UUID caseId, String why) {
        jdbc.sql("select order_id, order_number, customer_id, vendor_id from order_cases where id = :id").param("id", caseId)
                .query((rs, n) -> new OrderCaseEscalated(caseId, rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class), why))
                .optional().ifPresent(events::publishEvent);
    }

    private void owned(UUID vendorId, UUID caseId) {
        boolean mine = jdbc.sql("select exists (select 1 from order_cases where id = :id and vendor_id = :v)").param("id", caseId).param("v", vendorId)
                .query(Boolean.class).single();
        if (!mine) {
            throw BusinessException.notFound("CASE_NOT_FOUND", "Không tìm thấy khiếu nại.");
        }
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }
}
