package com.bonbon.backend.order;

import java.util.Set;

/** The order lifecycle (flows/order/README.md); the terminal states never change again. */
public enum OrderStatus {
    PENDING_PAYMENT,
    PLACED,
    CONFIRMED,
    PREPARING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    REJECTED,
    CANCELLED,
    NOT_DELIVERED;

    /** Orders that still count against the customer's open-order cap. */
    public static final Set<OrderStatus> OPEN = Set.of(PENDING_PAYMENT, PLACED, CONFIRMED, PREPARING, OUT_FOR_DELIVERY);

    public boolean isTerminal() {
        return this == DELIVERED || this == REJECTED || this == CANCELLED || this == NOT_DELIVERED;
    }
}
