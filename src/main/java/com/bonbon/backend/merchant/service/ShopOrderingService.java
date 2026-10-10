package com.bonbon.backend.merchant.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.bonbon.backend.merchant.OptionStatus;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.MenuItem;
import com.bonbon.backend.merchant.entity.Option;
import com.bonbon.backend.merchant.entity.OptionGroup;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.MenuItemRepository;
import com.bonbon.backend.merchant.repository.OptionGroupRepository;
import com.bonbon.backend.merchant.repository.OptionRepository;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The shop facts the order module validates against (place-order.md steps 1-3). */
@Service
class ShopOrderingService implements ShopOrdering {

    private final VendorRepository vendors;
    private final MenuItemRepository items;
    private final OptionGroupRepository groups;
    private final OptionRepository options;
    private final OptionService optionService;
    private final JdbcClient jdbc;

    ShopOrderingService(VendorRepository vendors, MenuItemRepository items, OptionGroupRepository groups,
            OptionRepository options, OptionService optionService, JdbcClient jdbc) {
        this.vendors = vendors;
        this.items = items;
        this.groups = groups;
        this.options = options;
        this.optionService = optionService;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> approvedVendorOwnedBy(UUID ownerUserId) {
        return vendors.findByOwnerUserId(ownerUserId).filter(v -> v.getStatus() == VendorStatus.APPROVED).map(Vendor::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> operatingVendorOwnedBy(UUID ownerUserId) {
        return vendors.findByOwnerUserId(ownerUserId).filter(v -> v.getStatus() == VendorStatus.APPROVED || v.getStatus() == VendorStatus.SUSPENDED).map(Vendor::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> ownerOf(UUID vendorId) {
        return vendors.findById(vendorId).map(Vendor::getOwnerUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderableShop> shop(UUID vendorId) {
        return vendors.findById(vendorId)
                .filter(v -> v.getStatus() == VendorStatus.APPROVED && v.getLat() != null && v.getLng() != null
                        && v.getDeliveryRadiusKm() != null && v.getDeliveryFee() != null)
                .map(this::toShop);
    }

    private OrderableShop toShop(Vendor v) {
        return new OrderableShop(v.getId(), v.getOwnerUserId(), v.getName(), v.getLat(), v.getLng(),
                v.getDeliveryRadiusKm().doubleValue(), v.getDeliveryFee(), v.getFreeDeliveryThreshold(),
                v.getMinOrderValue(), ShopHours.isOpen(v, ZonedDateTime.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrderableDish> dishes(UUID vendorId, Collection<UUID> dishIds) {
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> wanted = Set.copyOf(dishIds);
        List<MenuItem> found = items.findActiveByVendor(vendorId).stream().filter(i -> wanted.contains(i.getId())).toList();
        if (found.isEmpty()) {
            return Map.of();
        }
        OptionService.MenuOptionInfo info = optionService.forMenu(vendorId);
        Map<UUID, OptionGroup> groupById = groups.findActiveByVendor(vendorId).stream()
                .collect(Collectors.toMap(OptionGroup::getId, Function.identity()));
        Map<UUID, List<Option>> optionsByGroup = groupById.isEmpty() ? Map.of()
                : options.findLive(groupById.keySet()).stream().collect(Collectors.groupingBy(Option::getGroupId));

        Map<UUID, OrderableDish> result = new HashMap<>();
        for (MenuItem d : found) {
            List<OrderableGroup> attached = info.groupIdsByItem().getOrDefault(d.getId(), List.of()).stream()
                    .map(groupById::get).filter(Objects::nonNull)
                    .map(g -> new OrderableGroup(g.getId(), g.getName(), g.getMinSelect(), g.getMaxSelect(),
                            optionsByGroup.getOrDefault(g.getId(), Collections.emptyList()).stream()
                                    .map(o -> new OrderableOption(o.getId(), o.getName(), o.getPriceDelta(),
                                            o.getStatus() == OptionStatus.AVAILABLE)).toList()))
                    .toList();
            boolean orderable = !d.isSoldOut() && !info.blockedItems().contains(d.getId());
            result.put(d.getId(), new OrderableDish(d.getId(), d.getName(), d.getPrice(), d.getCategoryId(), orderable, attached));
        }
        return result;
    }

    @Override
    @Transactional
    public boolean takeStock(UUID dishId, int quantity) {
        // One conditional update: no read-then-write, so two orders cannot both take the last portion.
        return jdbc.sql("""
                update menu_items set stock_quantity = stock_quantity - :q, updated_at = now()
                where id = :id and stock_quantity is not null and stock_quantity >= :q""")
                .param("q", quantity).param("id", dishId).update() == 1
                || jdbc.sql("select count(*) from menu_items where id = :id and stock_quantity is null")
                        .param("id", dishId).query(Long.class).single() == 1;
    }

    @Override
    @Transactional
    public void returnStock(UUID dishId, int quantity) {
        jdbc.sql("update menu_items set stock_quantity = stock_quantity + :q, updated_at = now() where id = :id and stock_quantity is not null")
                .param("q", quantity).param("id", dishId).update();
    }
}
