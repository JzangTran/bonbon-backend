package com.bonbon.backend.marketplace.merchant.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.common.geo.GeoDistance;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import com.bonbon.backend.marketplace.merchant.VendorStatus;
import com.bonbon.backend.marketplace.merchant.entity.MenuItem;
import com.bonbon.backend.marketplace.merchant.entity.Option;
import com.bonbon.backend.marketplace.merchant.entity.OptionGroup;
import com.bonbon.backend.marketplace.merchant.entity.Vendor;
import com.bonbon.backend.marketplace.merchant.repository.MenuItemRepository;
import com.bonbon.backend.marketplace.merchant.repository.MenuSectionRepository;
import com.bonbon.backend.marketplace.merchant.repository.OptionGroupRepository;
import com.bonbon.backend.marketplace.merchant.repository.OptionRepository;
import com.bonbon.backend.marketplace.merchant.repository.VendorRepository;
import com.bonbon.backend.marketplace.servicearea.ServiceAreas;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Browsing shops (browse-vendors-in-area.md, view-vendor-menu.md). Straight-line distance on stored
 * coordinates, never a map API: a SQL prefilter on each shop's own radius keeps the candidate set small, then
 * the exact Haversine check decides. The prefilter starts with a latitude band as wide as the largest approved
 * radius, which an index can serve (V0041), so the per-row bounding box only runs on nearby shops.
 */
@Service
class ShopCatalogService implements ShopCatalog {

    private static final double KM_PER_DEGREE = 111.0;

    private final VendorRepository vendors;
    private final MenuSectionRepository sections;
    private final MenuItemRepository items;
    private final OptionGroupRepository groups;
    private final OptionRepository options;
    private final OptionService optionService;
    private final ObjectStorage storage;
    private final JdbcClient jdbc;
    private final ServiceAreas areas;

    ShopCatalogService(VendorRepository vendors, MenuSectionRepository sections, MenuItemRepository items,
            OptionGroupRepository groups, OptionRepository options, OptionService optionService, ObjectStorage storage,
            JdbcClient jdbc, ServiceAreas areas) {
        this.areas = areas;
        this.vendors = vendors;
        this.sections = sections;
        this.items = items;
        this.groups = groups;
        this.options = options;
        this.optionService = optionService;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Shop> shopsInArea(double lat, double lng, String query, Set<UUID> categoryIds) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasCategory = categoryIds != null;
        if (hasCategory && categoryIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select v.id from vendors v
                where v.status = 'APPROVED' and v.lat is not null and v.lng is not null
                  and v.delivery_radius_km is not null
                  and v.lat between :lat - (select max(r.delivery_radius_km) from vendors r where r.status = 'APPROVED') / :kmDeg
                                and :lat + (select max(r.delivery_radius_km) from vendors r where r.status = 'APPROVED') / :kmDeg
                  and :lat between v.lat - v.delivery_radius_km / :kmDeg and v.lat + v.delivery_radius_km / :kmDeg
                  and :lng between v.lng - v.delivery_radius_km / (:kmDeg * greatest(cos(radians(v.lat)), 0.01))
                              and v.lng + v.delivery_radius_km / (:kmDeg * greatest(cos(radians(v.lat)), 0.01))""");
        // A shop is listed when it has at least one dish a customer could order now.
        String dish = """
                exists (select 1 from menu_items i where i.vendor_id = v.id and i.archived_at is null
                        and i.status = 'AVAILABLE' and (i.stock_quantity is null or i.stock_quantity > 0)%s)""";
        if (hasCategory) {
            sql.append("\n  and ").append(dish.formatted(" and i.category_id in (:categories) and not i.category_restricted"));
        } else {
            sql.append("\n  and ").append(dish.formatted(""));
        }
        // A shop restricted for unpaid commission only shows in the plain area list, not in a search or a category.
        if (hasQuery || hasCategory) {
            sql.append("\n  and v.commission_stage in ('NONE', 'OVERDUE') and not v.performance_restricted");
        }
        if (hasQuery) {
            sql.append("""

                      and (unaccent(lower(v.name)) like unaccent(:pattern)
                           or exists (select 1 from menu_items q where q.vendor_id = v.id and q.archived_at is null and not q.category_restricted
                                      and unaccent(lower(q.name)) like unaccent(:pattern)))""");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("lat", lat).param("lng", lng)
                .param("kmDeg", KM_PER_DEGREE);
        if (hasCategory) {
            spec = spec.param("categories", List.copyOf(categoryIds));
        }
        if (hasQuery) {
            spec = spec.param("pattern", "%" + escapeLike(query.strip().toLowerCase()) + "%");
        }
        List<UUID> candidates = spec.query(UUID.class).list();

        ZonedDateTime now = ZonedDateTime.now();
        List<Vendor> found = vendors.findAllById(candidates);
        Set<UUID> restricted = found.stream().filter(v -> v.getCommissionStage().restrictsVisibility() || v.isPerformanceRestricted()).map(Vendor::getId)
                .collect(java.util.stream.Collectors.toSet());
        return found.stream()
                .filter(v -> v.getStatus() == VendorStatus.APPROVED)
                .filter(v -> GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng()) <= v.getDeliveryRadiusKm().doubleValue())
                .map(v -> shop(v, GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng()), now))
                .sorted(Comparator.comparing((Shop s) -> restricted.contains(s.id())).thenComparing((Shop s) -> !s.open()).thenComparing(Shop::distanceKm)
                        .thenComparing(Shop::name))
                .toList();
    }

    /** Whole percent off, as MenuItem.discountPercent, in SQL. */
    private static final String DISCOUNT_SQL = """
            case when i.original_price is not null and i.original_price > i.price and i.original_price > 0
                 then (i.original_price - i.price) * 100 / i.original_price else 0 end""";

    /** A dish a customer could order now (the same rule the area list uses). */
    private static final String ORDERABLE_SQL = """
            i.archived_at is null and i.status = 'AVAILABLE' and (i.stock_quantity is null or i.stock_quantity > 0)
            and not i.category_restricted""";

    @Override
    @Transactional(readOnly = true)
    public List<FeedEntry> feedEntries(Double lat, Double lng, double reachKm, Set<UUID> categoryIds) {
        if (categoryIds != null && categoryIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                select v.id, v.lat, v.lng, v.delivery_radius_km, v.rating_sum, v.rating_count,
                       (v.commission_stage not in ('NONE', 'OVERDUE') or v.performance_restricted) as restricted,
                       coalesce((select max(%s) from menu_items i where i.vendor_id = v.id and %s), 0) as max_discount
                from vendors v
                where v.status = 'APPROVED' and v.lat is not null and v.lng is not null and v.delivery_radius_km is not null
                  and exists (select 1 from menu_items i where i.vendor_id = v.id and %s%s)""".formatted(
                DISCOUNT_SQL, ORDERABLE_SQL, ORDERABLE_SQL, categoryIds == null ? "" : " and i.category_id in (:categories)"));
        if (lat != null && lng != null) {
            // a band as wide as the largest radius plus the reach, then each shop's own radius plus the reach
            sql.append("""

                      and v.lat between :lat - ((select max(r.delivery_radius_km) from vendors r where r.status = 'APPROVED') + :reach) / :kmDeg
                                    and :lat + ((select max(r.delivery_radius_km) from vendors r where r.status = 'APPROVED') + :reach) / :kmDeg
                      and abs(:lat - v.lat) * :kmDeg <= v.delivery_radius_km + :reach
                      and abs(:lng - v.lng) * :kmDeg * greatest(cos(radians(v.lat)), 0.01) <= v.delivery_radius_km + :reach""");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (lat != null && lng != null) {
            spec = spec.param("lat", lat).param("lng", lng).param("reach", reachKm).param("kmDeg", KM_PER_DEGREE);
        }
        if (categoryIds != null) {
            spec = spec.param("categories", List.copyOf(categoryIds));
        }
        return spec.query((rs, n) -> new FeedEntry(rs.getObject("id", UUID.class), rs.getDouble("lat"), rs.getDouble("lng"),
                rs.getDouble("delivery_radius_km"), rs.getInt("rating_sum"), rs.getInt("rating_count"), rs.getInt("max_discount"),
                rs.getBoolean("restricted"))).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedCard> feedCards(List<UUID> ids, Double lat, Double lng) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<FeedDish>> dishes = new HashMap<>();
        jdbc.sql("""
                select * from (
                    select i.id, i.vendor_id, i.name, i.photo_key, i.price, i.original_price,
                           row_number() over (partition by i.vendor_id
                                              order by (%s) desc, coalesce(s.sort_order, 0), i.sort_order, i.name) as rn
                    from menu_items i left join menu_sections s on s.id = i.section_id
                    where i.vendor_id in (:ids) and %s) t
                where rn <= 10
                order by vendor_id, rn""".formatted(DISCOUNT_SQL, ORDERABLE_SQL))
                .param("ids", ids)
                .query((rs, n) -> {
                    String photo = rs.getString("photo_key");
                    int price = rs.getInt("price");
                    Integer original = (Integer) rs.getObject("original_price");
                    dishes.computeIfAbsent(rs.getObject("vendor_id", UUID.class), k -> new ArrayList<>()).add(new FeedDish(
                            rs.getObject("id", UUID.class), rs.getString("name"), photo == null ? null : storage.publicUrl(photo),
                            price, original, MenuItem.discountPercent(price, original)));
                    return Boolean.TRUE;
                }).list();
        ZonedDateTime now = ZonedDateTime.now();
        Map<UUID, Vendor> found = new HashMap<>();
        for (Vendor v : vendors.findAllById(ids)) {
            if (v.getStatus() == VendorStatus.APPROVED) {
                found.put(v.getId(), v);
            }
        }
        List<FeedCard> cards = new ArrayList<>();
        for (UUID id : ids) {
            Vendor v = found.get(id);
            if (v == null) {
                continue;
            }
            Double distance = lat == null || lng == null ? null : GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng());
            cards.add(new FeedCard(shop(v, distance, now), dishes.getOrDefault(id, List.of())));
        }
        return cards;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopSchedule> schedule(UUID shopId) {
        return vendors.findById(shopId).filter(v -> v.getStatus() == VendorStatus.APPROVED).map(v -> {
            ZonedDateTime now = ZonedDateTime.now();
            if (ShopHours.isOpen(v, now)) {
                java.util.OptionalInt closes = ShopHours.closingMinute(v.getOpeningHours(), now);
                return new ShopSchedule(true, closes.isPresent() ? clock(closes.getAsInt()) : null, null, null);
            }
            java.util.OptionalInt opens = ShopHours.nextOpeningMinute(v.getOpeningHours(), now);
            return opens.isPresent() ? new ShopSchedule(false, null, opens.getAsInt() / 1440 + 1, clock(opens.getAsInt()))
                    : new ShopSchedule(false, null, null, null);
        });
    }

    private static String clock(int minuteOfWeek) {
        int minute = minuteOfWeek % 1440;
        return "%02d:%02d".formatted(minute / 60, minute % 60);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShopMenu> menu(UUID shopId, Double lat, Double lng) {
        return vendors.findById(shopId).filter(v -> v.getStatus() == VendorStatus.APPROVED).map(v -> {
            Double distance = lat == null || lng == null || v.getLat() == null ? null
                    : GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng());
            return new ShopMenu(shop(v, distance, ZonedDateTime.now()), sections(v));
        });
    }

    private List<Section> sections(Vendor vendor) {
        List<MenuItem> dishes = items.findActiveByVendor(vendor.getId());
        OptionService.MenuOptionInfo info = optionService.forMenu(vendor.getId());
        Map<UUID, OptionGroup> groupById = groups.findActiveByVendor(vendor.getId()).stream()
                .collect(Collectors.toMap(OptionGroup::getId, Function.identity()));
        Map<UUID, List<Option>> optionsByGroup = groupById.isEmpty() ? Map.of()
                : options.findLive(groupById.keySet()).stream().collect(Collectors.groupingBy(Option::getGroupId));

        Map<UUID, List<Item>> bySection = new LinkedHashMap<>();
        for (MenuItem d : dishes) {
            List<OptionGroup> attached = info.groupIdsByItem().getOrDefault(d.getId(), List.of()).stream()
                    .map(groupById::get).filter(java.util.Objects::nonNull).toList();
            List<MenuOptionGroup> groupViews = attached.stream().map(g -> new MenuOptionGroup(
                    g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                    optionsByGroup.getOrDefault(g.getId(), List.of()).stream().map(o -> new MenuOption(o.getId(),
                            o.getName(), o.getPriceDelta(), o.getStatus() == com.bonbon.backend.marketplace.merchant.OptionStatus.AVAILABLE,
                            o.isDefaultChoice())).toList())).toList();
            bySection.computeIfAbsent(d.getSectionId(), k -> new ArrayList<>()).add(new Item(d.getId(), d.getName(),
                    d.getDescription(), d.getPrice(), d.getOriginalPrice(), d.discountPercent(), d.getPhotoKey() == null ? null : storage.publicUrl(d.getPhotoKey()),
                    d.isSoldOut() || info.blockedItems().contains(d.getId()),
                    groupViews));
        }
        return sections.findByVendorIdOrderBySortOrderAscNameAsc(vendor.getId()).stream()
                .filter(s -> bySection.containsKey(s.getId()))
                .map(s -> new Section(s.getId(), s.getName(), bySection.get(s.getId()))).toList();
    }

    private Shop shop(Vendor v, Double distanceKm, ZonedDateTime now) {
        return new Shop(v.getId(), v.getName(), v.getLogoKey() == null ? null : storage.publicUrl(v.getLogoKey()),
                v.getCoverKey() == null ? null : storage.publicUrl(v.getCoverKey()), v.getFormattedAddress(),
                areas.find(v.getServiceAreaId()).map(ServiceAreas.Area::name).orElse(null),
                distanceKm == null ? null : Math.round(distanceKm * 100.0) / 100.0, ShopHours.isOpen(v, now),
                v.getDeliveryRadiusKm(), v.getDeliveryFee(), v.getFreeDeliveryThreshold(), v.getMinOrderValue(),
                v.getRatingCount() == 0 ? null : Math.round(v.getRatingSum() * 10.0 / v.getRatingCount()) / 10.0, v.getRatingCount());
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
