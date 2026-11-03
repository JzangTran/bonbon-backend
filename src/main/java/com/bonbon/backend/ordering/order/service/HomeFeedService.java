package com.bonbon.backend.ordering.order.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.geo.GeoDistance;
import com.bonbon.backend.marketplace.category.CategoryCatalog;
import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import com.bonbon.backend.marketplace.merchant.ShopCatalog.FeedEntry;
import com.bonbon.backend.ordering.order.dto.FeedPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The customer home page (owner decision 2026-11-03): shops 10 at a time, each with up to 10 dishes.
 *
 * <p>Working out which shops reach a point is the costly part, so it is done once per cell and kept for a few minutes: the
 * position is snapped to a cell of 0.01° (about 1.1 × 1.0 km), the database returns every shop whose radius could reach any point
 * of the cell (light rows: point, radius, rating, best discount), and that list is cached. Each request then only filters the
 * cached list for its exact point in memory (Haversine), sorts it and loads the 10 cards of its page. Without a position (a guest
 * who has not typed an address) there is one list for everyone, best rated first, cached the same way.
 */
@Service
public class HomeFeedService {

    public static final int PAGE_SIZE = 10;
    private static final double CELL_DEG = 0.01;
    /** Half the diagonal of a cell, rounded up: any point of the cell is within this of its centre. */
    private static final double CELL_REACH_KM = 0.8;
    private static final Duration AREA_TTL = Duration.ofMinutes(3);
    private static final Duration TOP_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHED_LISTS = 5_000;
    /** A shop with few reviews is pulled towards the average, so one 5-star review does not top the list. */
    private static final int PRIOR_REVIEWS = 5;
    static final int MAX_EXCLUDED = 100;

    private final ShopCatalog shops;
    private final CategoryCatalog categories;
    private final Map<String, Cached> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHED_LISTS;
        }
    };

    HomeFeedService(ShopCatalog shops, CategoryCatalog categories) {
        this.shops = shops;
        this.categories = categories;
    }

    /**
     * One page of the home page. {@code deals}: only shops with a discounted dish, biggest discount first. {@code excluded}: shops
     * left out (the customer's hidden shops, or a guest's, kept on the phone).
     */
    public FeedPage page(Double lat, Double lng, UUID displayCategoryId, boolean deals, Set<UUID> excluded, int page, Instant now) {
        if ((lat == null) != (lng == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Cần cả vĩ độ và kinh độ.");
        }
        if (lat != null && (lat.isNaN() || lng.isNaN() || lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Toạ độ không hợp lệ.");
        }
        if (page < 0 || page > 1_000) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang không hợp lệ.");
        }
        List<FeedEntry> entries = lat == null ? topRated(displayCategoryId, now) : reaching(lat, lng, displayCategoryId, now);
        int dealMax = entries.stream().filter(e -> !excluded.contains(e.id())).mapToInt(FeedEntry::maxDiscountPercent).max().orElse(0);
        var stream = entries.stream().filter(e -> !excluded.contains(e.id()));
        if (deals) {
            stream = stream.filter(e -> e.maxDiscountPercent() > 0)
                    .sorted(Comparator.comparing(FeedEntry::restricted).thenComparing(Comparator.comparingInt(FeedEntry::maxDiscountPercent).reversed()));
        }
        List<FeedEntry> ordered = stream.toList();
        int from = Math.min(page * PAGE_SIZE, ordered.size());
        int to = Math.min(from + PAGE_SIZE, ordered.size());
        List<UUID> ids = ordered.subList(from, to).stream().map(FeedEntry::id).toList();
        return new FeedPage(shops.feedCards(ids, lat, lng), page, PAGE_SIZE, to < ordered.size(), ordered.size(), dealMax);
    }

    /** Shops whose own radius covers the exact point: held-back shops last, then nearest first. */
    private List<FeedEntry> reaching(double lat, double lng, UUID displayCategoryId, Instant now) {
        double cellLat = (Math.floor(lat / CELL_DEG) + 0.5) * CELL_DEG;
        double cellLng = (Math.floor(lng / CELL_DEG) + 0.5) * CELL_DEG;
        String key = "area:%.3f:%.3f:%s".formatted(cellLat, cellLng, displayCategoryId);
        List<FeedEntry> candidates = cached(key, AREA_TTL, now,
                () -> shops.feedEntries(cellLat, cellLng, CELL_REACH_KM, leaves(displayCategoryId)));
        return candidates.stream()
                .filter(e -> GeoDistance.haversineKm(lat, lng, e.lat(), e.lng()) <= e.radiusKm())
                .sorted(Comparator.comparing(FeedEntry::restricted)
                        .thenComparingDouble(e -> GeoDistance.haversineKm(lat, lng, e.lat(), e.lng())))
                .toList();
    }

    /** Every shop, best rated first (the average pulled towards the overall average by a few imagined reviews). */
    private List<FeedEntry> topRated(UUID displayCategoryId, Instant now) {
        return cached("top:" + displayCategoryId, TOP_TTL, now, () -> {
            List<FeedEntry> all = shops.feedEntries(null, null, 0, leaves(displayCategoryId));
            long sum = all.stream().mapToLong(FeedEntry::ratingSum).sum();
            long count = all.stream().mapToLong(FeedEntry::ratingCount).sum();
            double mean = count == 0 ? 0 : (double) sum / count;
            return all.stream().sorted(Comparator.comparing(FeedEntry::restricted)
                    .thenComparing(Comparator.comparingDouble((FeedEntry e) ->
                            (PRIOR_REVIEWS * mean + e.ratingSum()) / (PRIOR_REVIEWS + e.ratingCount())).reversed())
                    .thenComparing(Comparator.comparingInt(FeedEntry::ratingCount).reversed())).toList();
        });
    }

    private Set<UUID> leaves(UUID displayCategoryId) {
        return displayCategoryId == null ? null : categories.displayCategoryLeaves(displayCategoryId);
    }

    private List<FeedEntry> cached(String key, Duration ttl, Instant now, java.util.function.Supplier<List<FeedEntry>> load) {
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && hit.until().isAfter(now)) {
                return hit.entries();
            }
        }
        List<FeedEntry> loaded = List.copyOf(load.get());
        synchronized (cache) {
            cache.put(key, new Cached(loaded, now.plus(ttl)));
        }
        return loaded;
    }

    /** Drops every cached list (tests; a shop that must disappear at once). */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private record Cached(List<FeedEntry> entries, Instant until) {
    }
}
