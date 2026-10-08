package com.bonbon.backend.order;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the order cases need from an order: its snapshot (lines, money) and who moved it to delivered, and the flag that
 * keeps the order's data visible to the shop while a case is open. Order cases live in {@code shopperformance}.
 */
public interface OrderIncidents {

    Optional<IncidentOrder> find(UUID orderId);

    /** Raises or clears the hold: while it is set the shop keeps seeing the customer's delivery details. */
    void setIncidentHold(UUID orderId, boolean hold);

    /**
     * {@code deliveredAt} is when the order reached its final status; {@code deliveredBy} is CUSTOMER (confirmed receipt),
     * SHOP or SYSTEM (after the automatic wait). {@code commission} is VAT-inclusive and covers the food only.
     */
    record IncidentOrder(UUID id, long number, UUID customerId, UUID vendorId, String status, String paymentMethod, Instant deliveredAt,
            String deliveredBy, int itemsTotal, int discount, int deliveryFee, int grandTotal, int commission, List<IncidentLine> lines) {
    }

    /** {@code lineTotal} includes the chosen options; {@code allocatedDiscount} is this line's share of the voucher. */
    record IncidentLine(UUID id, String name, int quantity, int lineTotal, int allocatedDiscount, int commission) {
    }
}
