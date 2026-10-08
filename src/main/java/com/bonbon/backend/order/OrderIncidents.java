package com.bonbon.backend.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/**
 * What the order cases need from an order: its snapshot (lines, money) and who moved it to delivered, and the flag that
 * keeps the order's data visible to the shop while a case is open. Order cases live in {@code shopperformance}.
 */
public interface OrderIncidents {

    Optional<IncidentOrder> find(UUID orderId);

    /**
     * Raises the hold only if the order is still out for delivery and not held, so a shop's no-show report and the customer
     * pressing "received" cannot both win. The hold also keeps the automatic "delivered after 3 hours" away from the order.
     */
    boolean holdIfOutForDelivery(UUID orderId);

    /**
     * Ends an order out for delivery after a no-show case: clears the hold, then moves it to {@code to} (DELIVERED,
     * NOT_DELIVERED or CANCELLED) as {@code by}. A cancelled order that was paid online is refunded in full, and a cash order
     * that ends DELIVERED counts as collected. Fails when the order is no longer out for delivery.
     */
    void endNoShow(UUID orderId, String to, ActorType by, UUID actorId, String reason);

    /**
     * Orders that reached PLACED and ended from the first instant (inclusive) to the second (exclusive), per shop, or only one shop when
     * a shop id is given: the denominator of a shop's failure rate. An order that never got past waiting for payment does not count.
     */
    Map<UUID, Long> finishedOrders(Instant from, Instant to, UUID vendorId);

    /** Raises or clears the hold: while it is set the shop keeps seeing the customer's delivery details. */
    void setIncidentHold(UUID orderId, boolean hold);

    /**
     * {@code deliveredAt} is when the order reached its final status; {@code deliveredBy} is CUSTOMER (confirmed receipt),
     * SHOP or SYSTEM (after the automatic wait). {@code commission} is VAT-inclusive and covers the food only.
     */
    record IncidentOrder(UUID id, long number, UUID customerId, String customerName, UUID vendorId, String status, String paymentMethod, Instant deliveredAt,
            String deliveredBy, Instant outForDeliveryAt, boolean held, int itemsTotal, int discount, int deliveryFee, int grandTotal, int commission, List<IncidentLine> lines) {
    }

    /** {@code lineTotal} includes the chosen options; {@code allocatedDiscount} is this line's share of the voucher. */
    record IncidentLine(UUID id, String name, int quantity, int lineTotal, int allocatedDiscount, int commission) {
    }
}
