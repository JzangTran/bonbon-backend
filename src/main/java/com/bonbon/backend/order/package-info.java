/**
 * Ordering from the customer side. For now: finding shops that deliver to an address and reading a shop's
 * menu (browse-vendors-in-area.md, view-vendor-menu.md). Shop data comes from
 * {@link com.bonbon.backend.merchant.ShopCatalog}. Reviews of delivered orders, the shop's replies and their
 * moderation (review-order.md, respond-to-review.md, moderate-review.md) also live here; the shop's average rating
 * is kept on the shop through {@link com.bonbon.backend.merchant.ShopRatings}.
 */
@ApplicationModule(displayName = "Order")
package com.bonbon.backend.order;

import org.springframework.modulith.ApplicationModule;
