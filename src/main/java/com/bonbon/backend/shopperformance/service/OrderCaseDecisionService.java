package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.payment.OnlinePayments;
import com.bonbon.backend.settlement.CaseHolds;
import com.bonbon.backend.shopperformance.OrderCaseDecided;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a case is settled, whoever settles it (the shop accepting, an administrator deciding). A conditional update
 * on the case status makes the decision happen once: a second decider finds nothing to change. Upholding moves the money
 * (ledger entries for the shop, a refund for the customer) in the same transaction; either outcome lets go of the held
 * amount and, when no other case of the order is waiting, of the order's incident hold.
 */
@Service
public class OrderCaseDecisionService {

    private static final Logger log = LoggerFactory.getLogger(OrderCaseDecisionService.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final CaseHolds holds;
    private final OnlinePayments payments;
    private final OrderIncidents orders;
    private final ApplicationEventPublisher events;
    private final CaseLog caseLog;

    OrderCaseDecisionService(JdbcClient jdbc, Clock clock, CaseHolds holds, OnlinePayments payments, OrderIncidents orders, ApplicationEventPublisher events, CaseLog caseLog) {
        this.caseLog = caseLog;
        this.jdbc = jdbc;
        this.clock = clock;
        this.holds = holds;
        this.payments = payments;
        this.orders = orders;
        this.events = events;
    }

    /**
     * Settles the case if it is still in one of {@code from}. Returns what was decided, or empty when someone got there first
     * (or the case is not in a state this decider may settle).
     */
    @Transactional
    public Optional<Decided> decide(UUID caseId, Collection<String> from, boolean uphold, ActorType by, UUID actorId, String reason) {
        String outcome = uphold ? "UPHELD" : "DISMISSED";
        Optional<Decided> decided = jdbc.sql("""
                update order_cases set status = :outcome, decided_by_type = :by, decided_by_id = :actor, decided_at = :at, reason = :reason, version = version + 1
                where id = :id and status in (:from)
                returning id, order_id, order_number, customer_id, vendor_id, type, refund_amount, commission_amount, no_show_outcome""")
                .param("outcome", outcome).param("by", by.name()).param("actor", actorId).param("at", Timestamp.from(clock.instant())).param("reason", reason)
                .param("id", caseId).param("from", from)
                .query((rs, n) -> new Decided(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getLong("order_number"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("type"), rs.getInt("refund_amount"),
                        rs.getInt("commission_amount"), rs.getString("no_show_outcome")))
                .optional();
        if (decided.isEmpty()) {
            return decided;
        }
        Decided d = decided.get();
        caseLog.add(d.caseId(), outcome, by, actorId, reason);
        if (uphold) {
            holds.bear(d.caseId(), d.vendorId(), d.orderId(), d.refundAmount(), d.commissionAmount(), "Khiếu nại đơn #" + d.orderNumber() + " được chấp nhận", by, actorId);
            if (d.refundAmount() > 0 && payments.refundForCase(d.orderId(), d.caseId(), d.refundAmount()).isEmpty()) {
                log.warn("Case {} was upheld but no refund could be queued for order {}", d.caseId(), d.orderId());
            }
        } else {
            holds.release(d.caseId());
            // A case that was upheld, reopened and now dismissed: what it cost the shop is given back by an opposite entry.
            holds.reverse(d.caseId(), d.vendorId(), d.orderId(), "Khiếu nại đơn #" + d.orderNumber() + " được xem lại và bác bỏ", by, actorId);
        }
        boolean waiting = jdbc.sql("select exists (select 1 from order_cases where order_id = :o and status in ('AWAITING_SHOP', 'AWAITING_CUSTOMER', 'OPEN'))")
                .param("o", d.orderId()).query(Boolean.class).single();
        if (!waiting) {
            orders.setIncidentHold(d.orderId(), false);
        }
        events.publishEvent(new OrderCaseDecided(d.caseId(), d.orderId(), d.orderNumber(), d.customerId(), d.vendorId(), d.type(), outcome, decidedBy(by),
                uphold ? d.refundAmount() : 0, reason, d.noShowOutcome()));
        return decided;
    }

    /**
     * Settles a no-show case and ends the order the way the outcome says: the customer was at fault (NOT_DELIVERED), did receive
     * it (DELIVERED) or the shop never came (CANCELLED, paid online orders refunded in full). Nothing is claimed in money, so
     * only the order moves. Empty when the case is no longer in one of {@code from}.
     */
    @Transactional
    public Optional<Decided> decideNoShow(UUID caseId, Collection<String> from, String noShowOutcome, ActorType by, UUID actorId, String reason) {
        Optional<Decided> decided = settleNoShow(caseId, from, noShowOutcome, by, actorId, reason);
        decided.ifPresent(d -> orders.endNoShow(d.orderId(), switch (noShowOutcome) {
            case "CUSTOMER_AT_FAULT" -> "NOT_DELIVERED";
            case "CUSTOMER_RECEIVED" -> "DELIVERED";
            default -> "CANCELLED";
        }, by, actorId, reason));
        return decided;
    }

    /** The order was already delivered by someone: the case is closed as "the customer received it" and the order is left alone. */
    @Transactional
    public Optional<Decided> decideNoShowWithoutMovingOrder(UUID caseId, String noShowOutcome, ActorType by, UUID actorId, String reason) {
        return settleNoShow(caseId, List.of("AWAITING_CUSTOMER", "OPEN"), noShowOutcome, by, actorId, reason);
    }

    private Optional<Decided> settleNoShow(UUID caseId, Collection<String> from, String noShowOutcome, ActorType by, UUID actorId, String reason) {
        int marked = jdbc.sql("update order_cases set no_show_outcome = :o where id = :id and type = 'CUSTOMER_NO_SHOW' and status in (:from)")
                .param("o", noShowOutcome).param("id", caseId).param("from", from).update();
        return marked == 0 ? Optional.empty() : decide(caseId, from, "CUSTOMER_AT_FAULT".equals(noShowOutcome), by, actorId, reason);
    }

    public record Decided(UUID caseId, UUID orderId, long orderNumber, UUID customerId, UUID vendorId, String type, int refundAmount, int commissionAmount,
            String noShowOutcome) {
    }

    private static String decidedBy(ActorType by) {
        return switch (by) {
            case ADMIN -> "ADMIN";
            case SYSTEM -> "SYSTEM";
            case CUSTOMER -> "CUSTOMER";
            default -> "SHOP";
        };
    }
}
