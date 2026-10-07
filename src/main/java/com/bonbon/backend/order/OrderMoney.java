package com.bonbon.backend.order;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The money figures of an order, for settlement: what was charged and the commission snapshotted when it was placed. */
public interface OrderMoney {

    Optional<Money> of(UUID orderId);

    /** The display number of each order (the ones that exist). */
    Map<UUID, Long> numbers(Collection<UUID> orderIds);

    /**
     * {@code paymentMethod} is COD or ONLINE (an online order only exists as a real order once it is paid).
     * {@code commission} is VAT-inclusive and covers the food value only; {@code grandTotal} includes the delivery fee.
     */
    record Money(UUID orderId, UUID vendorId, String paymentMethod, int itemsTotal, int discount, int deliveryFee, int grandTotal, int commission) {
    }
}
