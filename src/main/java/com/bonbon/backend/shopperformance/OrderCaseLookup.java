package com.bonbon.backend.shopperformance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The cases filed about an order, for the administrators' order lookup. */
public interface OrderCaseLookup {

    /** Every case of the order (a customer complaint and a no-show report can both exist), oldest first. */
    List<CaseRef> ofOrder(UUID orderId);

    record CaseRef(UUID id, String type, String status, int refundAmount, Instant openedAt) {
    }
}
