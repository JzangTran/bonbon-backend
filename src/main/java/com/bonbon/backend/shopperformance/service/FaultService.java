package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.shopperformance.OrderCaseDecided;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Writes down every order that failed because of the shop (flows/shop-performance/README.md), in the same transaction as the
 * change that failed it. The rate is worked out from these rows later, so what counts is decided in one place, here: a shop that
 * declined, did not answer, did not hand over in time, cancelled after confirming, was found to owe a whole refund, or never
 * showed up. A customer's own cancellation, a payment that timed out, a partial refund and a no-show the customer caused never do.
 */
@Service
public class FaultService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final OrderIncidents orders;

    FaultService(JdbcClient jdbc, Clock clock, OrderIncidents orders) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.orders = orders;
    }

    @EventListener
    void onOrderStatusChanged(OrderStatusChanged e) {
        String type = typeOf(e);
        if (type != null) {
            record(e.vendorId(), e.orderId(), e.number(), type, null);
        }
    }

    /** What the status change says about whose fault it was; null when the shop is not to blame. */
    static String typeOf(OrderStatusChanged e) {
        boolean shop = e.by() == ActorType.SHOP_ACCOUNT || e.by() == ActorType.MAIN_ACCOUNT || e.by() == ActorType.MEMBER;
        if (e.to() == OrderStatus.REJECTED) {
            return e.by() == ActorType.SYSTEM ? "NO_RESPONSE" : "SHOP_REJECTED";
        }
        if (e.to() == OrderStatus.CANCELLED) {
            if (shop) {
                return "SHOP_CANCELLED";
            }
            if (e.by() == ActorType.SYSTEM && e.from() == OrderStatus.OUT_FOR_DELIVERY) {
                return "NO_SHOW_SHOP_AT_FAULT";
            }
            if (e.by() == ActorType.SYSTEM && (e.from() == OrderStatus.CONFIRMED || e.from() == OrderStatus.PREPARING)) {
                return "HANDOVER_TIMEOUT";
            }
        }
        return null;
    }

    /**
     * A case that refunds the whole order is the shop's fault, whether the shop accepted it or an administrator upheld it; a
     * partial refund is not. A decision that is later reversed takes the fault back.
     */
    @EventListener
    void onCaseDecided(OrderCaseDecided e) {
        if ("CUSTOMER_NO_SHOW".equals(e.type())) {
            return;
        }
        if ("UPHELD".equals(e.outcome()) && coversWholeOrder(e)) {
            record(e.vendorId(), e.orderId(), e.orderNumber(), "INCIDENT_FULL_REFUND", e.caseId());
        } else if ("DISMISSED".equals(e.outcome())) {
            jdbc.sql("delete from shop_fault_events where case_id = :c").param("c", e.caseId()).update();
        }
    }

    private boolean coversWholeOrder(OrderCaseDecided e) {
        if ("NOT_RECEIVED".equals(e.type())) {
            return true;
        }
        return orders.find(e.orderId()).map(order -> order.lines().stream().allMatch(line -> jdbc.sql(
                "select coalesce(sum(quantity), 0) from order_case_items where case_id = :c and order_item_id = :i").param("c", e.caseId()).param("i", line.id())
                .query(Integer.class).single() >= line.quantity())).orElse(false);
    }

    private void record(UUID vendorId, UUID orderId, long orderNumber, String type, UUID caseId) {
        jdbc.sql("""
                insert into shop_fault_events (vendor_id, order_id, order_number, type, case_id, occurred_at)
                values (:v, :o, :n, :t, :c, :at) on conflict (order_id, type) do nothing""")
                .param("v", vendorId).param("o", orderId).param("n", orderNumber).param("t", type).param("c", caseId).param("at", Timestamp.from(clock.instant())).update();
    }
}
