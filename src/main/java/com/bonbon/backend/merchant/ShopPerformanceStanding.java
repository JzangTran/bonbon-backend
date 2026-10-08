package com.bonbon.backend.merchant;

import java.util.UUID;

/**
 * Whether a shop is restricted in visibility because of its performance (flows/shop-performance/README.md). The decision is
 * made by the shop performance module; this module applies it: a restricted shop drops out of search and category results and
 * sorts last in the area list, and can still be opened and ordered from directly. Unpaid commission restricts the same way for
 * its own reason ({@link ShopCommissionStanding}); either one is enough.
 */
public interface ShopPerformanceStanding {

    void setRestricted(UUID vendorId, boolean restricted);
}
