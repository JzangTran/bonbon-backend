package com.bonbon.backend.merchant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may read about shops that customers can browse: only {@code APPROVED} shops, and only
 * what is public. The shop's own management API stays inside this module.
 */
public interface ShopCatalog {

    /**
     * Shops whose own delivery radius covers the point, open ones first, then nearest first. {@code query}
     * (shop or dish name, ignoring case and diacritics) and {@code categoryIds} (a dish category and its
     * descendants) narrow the result; both may be null.
     */
    List<Shop> shopsInArea(double lat, double lng, String query, java.util.Set<UUID> categoryIds);

    /** The shop's menu, or empty when the shop does not exist or is not approved. */
    Optional<ShopMenu> menu(UUID shopId, Double lat, Double lng);

    /**
     * {@code distanceKm} is null when the caller gave no position. {@code open} is the one rule of pause-orders.md.
     * {@code ratingAverage} (one decimal) is null while the shop has no visible review.
     */
    record Shop(
            UUID id,
            String name,
            String address,
            Double distanceKm,
            boolean open,
            BigDecimal deliveryRadiusKm,
            Integer deliveryFee,
            Integer freeDeliveryThreshold,
            Integer minOrderValue,
            Double ratingAverage,
            int ratingCount) {
    }

    record ShopMenu(Shop shop, List<Section> sections) {
    }

    record Section(UUID id, String name, List<Item> items) {
    }

    /** {@code soldOut} is effective (switched off, no stock, or a required group cannot be filled); stock itself is never shown. */
    record Item(UUID id, String name, String description, int price, String photoUrl, boolean soldOut,
            List<MenuOptionGroup> optionGroups) {
    }

    record MenuOptionGroup(UUID id, String name, int min, int max, List<MenuOption> options) {
    }

    record MenuOption(UUID id, String name, int priceDelta, boolean available, boolean defaultChoice) {
    }
}
