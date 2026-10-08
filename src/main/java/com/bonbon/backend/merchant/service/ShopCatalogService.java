package com.bonbon.backend.merchant.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.common.geo.GeoDistance;
import com.bonbon.backend.common.storage.ObjectStorage;
import com.bonbon.backend.merchant.ShopCatalog;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.MenuItem;
import com.bonbon.backend.merchant.entity.Option;
import com.bonbon.backend.merchant.entity.OptionGroup;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.MenuItemRepository;
import com.bonbon.backend.merchant.repository.MenuSectionRepository;
import com.bonbon.backend.merchant.repository.OptionGroupRepository;
import com.bonbon.backend.merchant.repository.OptionRepository;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Browsing shops (browse-vendors-in-area.md, view-vendor-menu.md). Straight-line distance on stored
 * coordinates, never a map API: a SQL prefilter on each shop's own radius keeps the candidate set small, then
 * the exact Haversine check decides.
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

    ShopCatalogService(VendorRepository vendors, MenuSectionRepository sections, MenuItemRepository items,
            OptionGroupRepository groups, OptionRepository options, OptionService optionService, ObjectStorage storage,
            JdbcClient jdbc) {
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
                  and :lat between v.lat - v.delivery_radius_km / :kmDeg and v.lat + v.delivery_radius_km / :kmDeg
                  and :lng between v.lng - v.delivery_radius_km / (:kmDeg * greatest(cos(radians(v.lat)), 0.01))
                              and v.lng + v.delivery_radius_km / (:kmDeg * greatest(cos(radians(v.lat)), 0.01))""");
        // A shop is listed when it has at least one dish a customer could order now.
        String dish = """
                exists (select 1 from menu_items i where i.vendor_id = v.id and i.archived_at is null
                        and i.status = 'AVAILABLE' and (i.stock_quantity is null or i.stock_quantity > 0)%s)""";
        if (hasCategory) {
            sql.append("\n  and ").append(dish.formatted(" and i.category_id in (:categories)"));
        } else {
            sql.append("\n  and ").append(dish.formatted(""));
        }
        // A shop restricted for unpaid commission only shows in the plain area list, not in a search or a category.
        if (hasQuery || hasCategory) {
            sql.append("\n  and v.commission_stage in ('NONE', 'OVERDUE')");
        }
        if (hasQuery) {
            sql.append("""

                      and (unaccent(lower(v.name)) like unaccent(:pattern)
                           or exists (select 1 from menu_items q where q.vendor_id = v.id and q.archived_at is null
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
        Set<UUID> restricted = found.stream().filter(v -> v.getCommissionStage().restrictsVisibility()).map(Vendor::getId)
                .collect(java.util.stream.Collectors.toSet());
        return found.stream()
                .filter(v -> v.getStatus() == VendorStatus.APPROVED)
                .filter(v -> GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng()) <= v.getDeliveryRadiusKm().doubleValue())
                .map(v -> shop(v, GeoDistance.haversineKm(lat, lng, v.getLat(), v.getLng()), now))
                .sorted(Comparator.comparing((Shop s) -> restricted.contains(s.id())).thenComparing((Shop s) -> !s.open()).thenComparing(Shop::distanceKm)
                        .thenComparing(Shop::name))
                .toList();
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
                            o.getName(), o.getPriceDelta(), o.getStatus() == com.bonbon.backend.merchant.OptionStatus.AVAILABLE,
                            o.isDefaultChoice())).toList())).toList();
            bySection.computeIfAbsent(d.getSectionId(), k -> new ArrayList<>()).add(new Item(d.getId(), d.getName(),
                    d.getDescription(), d.getPrice(), d.getPhotoKey() == null ? null : storage.publicUrl(d.getPhotoKey()),
                    d.isSoldOut() || info.blockedItems().contains(d.getId()),
                    groupViews));
        }
        return sections.findByVendorIdOrderBySortOrderAscNameAsc(vendor.getId()).stream()
                .filter(s -> bySection.containsKey(s.getId()))
                .map(s -> new Section(s.getId(), s.getName(), bySection.get(s.getId()))).toList();
    }

    private static Shop shop(Vendor v, Double distanceKm, ZonedDateTime now) {
        return new Shop(v.getId(), v.getName(), v.getFormattedAddress(),
                distanceKm == null ? null : Math.round(distanceKm * 100.0) / 100.0, ShopHours.isOpen(v, now),
                v.getDeliveryRadiusKm(), v.getDeliveryFee(), v.getFreeDeliveryThreshold(), v.getMinOrderValue(),
                v.getRatingCount() == 0 ? null : Math.round(v.getRatingSum() * 10.0 / v.getRatingCount()) / 10.0, v.getRatingCount());
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
