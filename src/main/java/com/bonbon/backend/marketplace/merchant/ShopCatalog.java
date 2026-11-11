package com.bonbon.backend.marketplace.merchant;

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

    /**
     * The home page's raw material, light enough to cache: every approved shop with a dish to order (in {@code categoryIds} when
     * given), with its point, radius, rating and best discount. With {@code lat}/{@code lng}, only shops whose radius could reach
     * a point within {@code reachKm} of it (the caller checks the exact point); without, every shop.
     */
    List<FeedEntry> feedEntries(Double lat, Double lng, double reachKm, java.util.Set<UUID> categoryIds);

    /** The cards of a page of the home page, in the order of {@code ids}: the shop and up to 10 dishes it has now, discounted first. */
    List<FeedCard> feedCards(List<UUID> ids, Double lat, Double lng);

    /**
     * {@code restricted}: the shop is held back (unpaid commission, performance) and goes last. {@code maxDiscountPercent} is the
     * best "Giảm X%" among its dishes on sale now, 0 when none.
     */
    record FeedEntry(UUID id, double lat, double lng, double radiusKm, int ratingSum, int ratingCount, int maxDiscountPercent,
            boolean restricted) {
    }

    record FeedCard(Shop shop, List<FeedDish> dishes) {
    }

    record FeedDish(UUID id, String name, String photoUrl, int price, Integer originalPrice, int discountPercent) {
    }

    /** The shop's menu, or empty when the shop does not exist or is not approved. */
    Optional<ShopMenu> menu(UUID shopId, Double lat, Double lng);

    /** Whether an approved shop is open now and when that next changes (Vietnam time, "HH:mm"; {@code opensDay} 1 is Monday). */
    Optional<ShopSchedule> schedule(UUID shopId);

    record ShopSchedule(boolean open, String closesAt, Integer opensDay, String opensAt) {
    }

    /**
     * {@code distanceKm} is null when the caller gave no position. {@code open} is the one rule of pause-orders.md.
     * {@code ratingAverage} (one decimal) is null while the shop has no visible review.
     * {@code serviceAreaName} is the area the shop is in, null for a shop approved before areas existed.
     */
    record Shop(
            UUID id,
            String name,
            String logoUrl,
            String coverUrl,
            String address,
            String serviceAreaName,
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
    record Item(UUID id, String name, String description, int price, Integer originalPrice, int discountPercent, String photoUrl,
            boolean soldOut, List<MenuOptionGroup> optionGroups) {
    }

    record MenuOptionGroup(UUID id, String name, int min, int max, List<MenuOption> options) {
    }

    record MenuOption(UUID id, String name, int priceDelta, boolean available, boolean defaultChoice) {
    }
}
