package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopNames;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.settlement.CaseHolds;
import com.bonbon.backend.shopperformance.OrderCaseReopened;
import com.bonbon.backend.shopperformance.dto.AdminCaseRequests;
import com.bonbon.backend.shopperformance.dto.AdminCaseViews;
import com.bonbon.backend.shopperformance.dto.CaseRequests;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One queue for every case nobody settled (flows/shop-performance/review-order-cases.md): the cases the shop disputed or
 * did not answer. An administrator reads the evidence and decides upheld or dismissed with a reason both sides see; the
 * decision itself, and the money it moves, is {@link OrderCaseDecisionService}.
 */
@Service
public class AdminCaseService {

    private static final Set<String> STATUSES = Set.of("AWAITING_SHOP", "AWAITING_CUSTOMER", "OPEN", "UPHELD", "DISMISSED");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ShopNames shopNames;
    private final OrderCaseService cases;
    private final OrderCaseDecisionService decisions;
    private final CaseHolds holds;
    private final OrderIncidents orders;
    private final CaseLog log;
    private final ApplicationEventPublisher events;

    AdminCaseService(JdbcClient jdbc, Clock clock, ShopNames shopNames, OrderCaseService cases, OrderCaseDecisionService decisions, CaseHolds holds,
            OrderIncidents orders, CaseLog log, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.shopNames = shopNames;
        this.cases = cases;
        this.decisions = decisions;
        this.holds = holds;
        this.orders = orders;
        this.log = log;
        this.events = events;
    }

    /** {@code status} defaults to the queue (OPEN, oldest first); the other statuses show newest first. */
    @Transactional(readOnly = true)
    public AdminCaseViews.Page list(String status, String type, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String s = status == null ? "OPEN" : status;
        if (!STATUSES.contains(s)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` không hợp lệ.");
        }
        if (type != null && !Set.of("NOT_RECEIVED", "MISSING_ITEM", "WRONG_ITEM", "QUALITY", "CUSTOMER_NO_SHOW", "SUSPECTED_FAKE").contains(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "`type` không hợp lệ.");
        }
        String where = " where status = :s and (cast(:t as text) is null or type = :t)";
        long total = jdbc.sql("select count(*) from order_cases" + where).param("s", s).param("t", type).query(Long.class).single();
        record Row(UUID id, long number, String type, String status, int refund, int commission, UUID vendor, String customer, String response, java.time.Instant openedAt) {
        }
        List<Row> rows = jdbc.sql("select id, order_number, type, status, refund_amount, commission_amount, vendor_id, customer_name, shop_response, opened_at from order_cases"
                + where + " order by opened_at " + ("OPEN".equals(s) ? "asc" : "desc") + ", id limit :limit offset :offset")
                .param("s", s).param("t", type).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getLong("order_number"), rs.getString("type"), rs.getString("status"), rs.getInt("refund_amount"),
                        rs.getInt("commission_amount"), rs.getObject("vendor_id", UUID.class), rs.getString("customer_name"), rs.getString("shop_response"),
                        rs.getTimestamp("opened_at").toInstant())).list();
        Map<UUID, String> names = shopNames.names(rows.stream().map(Row::vendor).distinct().toList());
        List<AdminCaseViews.Summary> items = rows.stream().map(r -> new AdminCaseViews.Summary(r.id(), r.number(), r.type(), r.status(), r.refund(),
                Math.max(r.refund() - r.commission(), 0), r.vendor(), names.getOrDefault(r.vendor(), "(đã xoá)"), r.customer(), r.response(), r.openedAt())).toList();
        return new AdminCaseViews.Page(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public AdminCaseViews.Detail detail(UUID caseId) {
        var row = jdbc.sql("select customer_id, vendor_id, status, shop_response from order_cases where id = :id").param("id", caseId)
                .query((rs, n) -> Map.of("customer", rs.getObject("customer_id", UUID.class), "vendor", rs.getObject("vendor_id", UUID.class), "status", rs.getString("status"),
                        "response", String.valueOf(rs.getString("shop_response"))))
                .optional().orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Không tìm thấy khiếu nại."));
        UUID customer = (UUID) row.get("customer");
        UUID vendor = (UUID) row.get("vendor");
        List<AdminCaseViews.LogEntry> entries = jdbc.sql("select action, actor_type, detail, created_at from order_case_log where case_id = :c order by created_at, id")
                .param("c", caseId).query((rs, n) -> new AdminCaseViews.LogEntry(rs.getString("action"), actor(rs.getString("actor_type")), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant())).list();
        boolean reopened = entries.stream().anyMatch(e -> "REOPENED".equals(e.action()));
        return new AdminCaseViews.Detail(cases.viewForShop(caseId), customer, vendor, shopNames.names(List.of(vendor)).getOrDefault(vendor, "(đã xoá)"),
                history("customer_id", customer, caseId), history("vendor_id", vendor, caseId), "null".equals(row.get("response")) && !"AWAITING_SHOP".equals(row.get("status")), reopened,
                entries);
    }

    /** Settles an open case. Uphold only some lines or quantities with {@code lines}; the amounts are worked out again from what was stored when it was filed. */
    @Transactional
    public CaseViews.Case decide(CurrentPrincipal admin, UUID caseId, AdminCaseRequests.Decide request) {
        boolean uphold = "UPHELD".equals(request.outcome());
        var found = jdbc.sql("select status, type from order_cases where id = :id for update").param("id", caseId)
                .query((rs, n) -> new String[] { rs.getString("status"), rs.getString("type") }).optional()
                .orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Không tìm thấy khiếu nại."));
        if (!"OPEN".equals(found[0])) {
            throw BusinessException.conflict("CASE_NOT_OPEN", "Khiếu nại này không ở hàng đợi quản trị: chưa đến lượt hoặc đã được quyết.").withProperty("status", found[0]);
        }
        boolean narrow = request.lines() != null && !request.lines().isEmpty();
        if (narrow) {
            if (!uphold || "NOT_RECEIVED".equals(found[1]) || reopened(caseId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LINES_NOT_ALLOWED",
                        "Chỉ chấp nhận một phần được với kết quả UPHELD, khiếu nại thiếu món, sai món hoặc chất lượng, và chưa từng mở lại.");
            }
            narrow(caseId, request.lines());
        }
        decisions.decide(caseId, List.of("OPEN"), uphold, ActorType.ADMIN, admin.id(), request.reason().strip())
                .orElseThrow(() -> BusinessException.conflict("CASE_NOT_OPEN", "Khiếu nại này vừa được người khác quyết."));
        return cases.viewForShop(caseId);
    }

    /** A decided case is looked at again, once, with the reason on record. What it cost the shop is corrected only if it is then dismissed. */
    @Transactional
    public CaseViews.Case reopen(CurrentPrincipal admin, UUID caseId, String reason) {
        var found = jdbc.sql("select status, order_id, order_number, customer_id, vendor_id, refund_amount, commission_amount from order_cases where id = :id for update")
                .param("id", caseId).query((rs, n) -> new Object[] { rs.getString("status"), rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getInt("refund_amount"), rs.getInt("commission_amount") })
                .optional().orElseThrow(() -> BusinessException.notFound("CASE_NOT_FOUND", "Không tìm thấy khiếu nại."));
        String status = (String) found[0];
        if (!"UPHELD".equals(status) && !"DISMISSED".equals(status)) {
            throw BusinessException.conflict("CASE_NOT_DECIDED", "Chỉ mở lại được khiếu nại đã có quyết định.").withProperty("status", status);
        }
        if (reopened(caseId)) {
            throw BusinessException.conflict("CASE_ALREADY_REOPENED", "Khiếu nại này đã được mở lại một lần.");
        }
        jdbc.sql("""
                update order_cases set status = 'OPEN', decided_by_type = null, decided_by_id = null, decided_at = null, reason = null, version = version + 1
                where id = :id""").param("id", caseId).update();
        log.add(caseId, "REOPENED", ActorType.ADMIN, admin.id(), reason.strip());
        UUID orderId = (UUID) found[1];
        orders.setIncidentHold(orderId, true);
        if ("DISMISSED".equals(status)) {
            // Nothing was taken from the shop; hold what it would bear again until the second decision.
            holds.place(caseId, (UUID) found[4], Math.max((int) found[5] - (int) found[6], 0));
        }
        events.publishEvent(new OrderCaseReopened(caseId, orderId, (long) found[2], (UUID) found[3], (UUID) found[4], reason.strip()));
        return cases.viewForShop(caseId);
    }

    // --- internals

    private boolean reopened(UUID caseId) {
        return jdbc.sql("select exists (select 1 from order_case_log where case_id = :c and action = 'REOPENED')").param("c", caseId).query(Boolean.class).single();
    }

    private void narrow(UUID caseId, List<CaseRequests.Line> requested) {
        record Item(UUID id, int quantity, int refund, int commission) {
        }
        Map<UUID, Item> items = new LinkedHashMap<>();
        jdbc.sql("select order_item_id, quantity, refund_amount, commission_amount from order_case_items where case_id = :c").param("c", caseId)
                .query((rs, n) -> new Item(rs.getObject("order_item_id", UUID.class), rs.getInt("quantity"), rs.getInt("refund_amount"), rs.getInt("commission_amount")))
                .list().forEach(i -> items.put(i.id(), i));
        Set<UUID> seen = new HashSet<>();
        List<Item> kept = new ArrayList<>();
        int refund = 0;
        int commission = 0;
        for (CaseRequests.Line line : requested) {
            Item item = items.get(line.orderItemId());
            if (item == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LINE_NOT_IN_CASE", "Có món không thuộc khiếu nại này.").withProperty("orderItemId", line.orderItemId());
            }
            if (!seen.add(line.orderItemId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LINE_REPEATED", "Mỗi món chỉ chọn một lần.").withProperty("orderItemId", line.orderItemId());
            }
            if (line.quantity() < 1 || line.quantity() > item.quantity()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "QUANTITY_INVALID", "Số phần phải từ 1 đến " + item.quantity() + ".").withProperty("orderItemId", line.orderItemId());
            }
            int lineRefund = OrderCaseService.share(item.refund(), line.quantity(), item.quantity());
            int lineCommission = OrderCaseService.share(item.commission(), line.quantity(), item.quantity());
            jdbc.sql("update order_case_items set quantity = :q, refund_amount = :r, commission_amount = :m where case_id = :c and order_item_id = :i")
                    .param("q", line.quantity()).param("r", lineRefund).param("m", lineCommission).param("c", caseId).param("i", line.orderItemId()).update();
            refund += lineRefund;
            commission += lineCommission;
            kept.add(item);
        }
        for (Item item : items.values()) {
            if (!seen.contains(item.id())) {
                jdbc.sql("delete from order_case_items where case_id = :c and order_item_id = :i").param("c", caseId).param("i", item.id()).update();
            }
        }
        jdbc.sql("update order_cases set refund_amount = :r, commission_amount = :m where id = :id").param("r", refund).param("m", commission).param("id", caseId).update();
    }

    private AdminCaseViews.History history(String column, UUID id, UUID except) {
        return jdbc.sql("""
                select count(*) as total, count(*) filter (where status = 'UPHELD') as upheld, count(*) filter (where status = 'DISMISSED') as dismissed,
                       count(*) filter (where status = 'DISMISSED' and decided_at >= :since) as recent
                from order_cases where %s = :id and id <> :except""".formatted(column))
                .param("id", id).param("except", except).param("since", Timestamp.from(clock.instant().minus(java.time.Duration.ofDays(90))))
                .query((rs, n) -> new AdminCaseViews.History(rs.getLong("total"), rs.getLong("upheld"), rs.getLong("dismissed"), rs.getLong("recent"))).single();
    }

    private static String actor(String type) {
        return switch (type) {
            case "ADMIN", "SYSTEM", "CUSTOMER" -> type;
            default -> "SHOP";
        };
    }
}
