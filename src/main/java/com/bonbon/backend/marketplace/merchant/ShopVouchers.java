package com.bonbon.backend.marketplace.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the order module needs from a shop's vouchers (manage-vouchers.md, place-order.md). The shop funds a voucher: it comes off
 * the food value, so commission is charged on what is left. A use is reserved atomically with the order and released when the
 * order ends cancelled or rejected.
 */
public interface ShopVouchers {

    /** The shop's vouchers a customer can use now, soonest ending first; empty for a shop that is not approved. */
    List<Offer> offers(UUID vendorId);

    /** Works out the discount on this food value, or says why the voucher does not apply. Nothing is reserved yet. */
    Quote quote(UUID vendorId, String code, int itemsTotal);

    /** Takes one use, atomically: false when the last use has just gone. */
    boolean reserve(UUID voucherId);

    /** Gives a use back (the order ended cancelled or rejected). */
    void release(UUID voucherId);

    /** The code a voucher had, for showing on an order. */
    Optional<String> codeOf(UUID voucherId);

    record Offer(UUID id, String code, String discountType, int discountValue, Integer maxDiscount, Integer minOrderValue,
            Instant validFrom, Instant validTo, Integer maxPerCustomer) {
    }

    record Quote(UUID voucherId, int discount, Integer maxPerCustomer) {
    }
}
