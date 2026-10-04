package com.bonbon.backend.merchant;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the order module needs from a shop to validate and price an order: the shop as it is right now, its
 * orderable dishes with their option groups, and the stock counter. Prices come from here, never from a client.
 */
public interface ShopOrdering {

    /** The shop, or empty when it does not exist or is not approved. */
    Optional<OrderableShop> shop(UUID vendorId);

    /** The shop's current dishes among {@code dishIds}; archived or foreign ids are simply absent. */
    Map<UUID, OrderableDish> dishes(UUID vendorId, Collection<UUID> dishIds);

    /** Takes {@code quantity} from the dish stock; false when fewer are left. Dishes without stock always succeed. */
    boolean takeStock(UUID dishId, int quantity);

    /** Gives portions back (cancelled or rejected order). Dishes without stock ignore it. */
    void returnStock(UUID dishId, int quantity);

    /** {@code open} is the one rule of pause-orders.md: approved, accepting orders and inside an opening window now. */
    record OrderableShop(UUID id, UUID ownerUserId, String name, double lat, double lng, double radiusKm, int deliveryFee,
            Integer freeDeliveryThreshold, Integer minOrderValue, boolean open) {
    }

    /** {@code orderable} is false when the dish is switched off, out of stock, or a required group cannot be filled. */
    record OrderableDish(UUID id, String name, int price, UUID categoryId, boolean orderable, List<OrderableGroup> groups) {
    }

    record OrderableGroup(UUID id, String name, int min, int max, List<OrderableOption> options) {
    }

    record OrderableOption(UUID id, String name, int priceDelta, boolean available) {
    }
}
