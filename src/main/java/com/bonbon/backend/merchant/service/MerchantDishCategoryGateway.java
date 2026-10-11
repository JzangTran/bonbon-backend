package com.bonbon.backend.merchant.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.category.DishCategoryGateway;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What category moderation needs of the menu: read a dish, move it to another category and hide it from search. The flag is not part of
 * the {@code MenuItem} entity (only moderation and search touch it), so it is written directly.
 */
@Component
class MerchantDishCategoryGateway implements DishCategoryGateway {

    private final JdbcClient jdbc;
    private final VendorRepository vendors;

    MerchantDishCategoryGateway(JdbcClient jdbc, VendorRepository vendors) {
        this.jdbc = jdbc;
        this.vendors = vendors;
    }

    @Override
    public Optional<Dish> dish(UUID dishId) {
        return jdbc.sql("select i.id, i.vendor_id, v.name as shop_name, i.name, i.category_id, i.archived_at is not null as archived from menu_items i join vendors v on v.id = i.vendor_id where i.id = :id")
                .param("id", dishId)
                .query((rs, n) -> new Dish(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("shop_name"), rs.getString("name"),
                        rs.getObject("category_id", UUID.class), rs.getBoolean("archived")))
                .optional();
    }

    @Override
    public void setCategory(UUID dishId, UUID categoryId) {
        jdbc.sql("update menu_items set category_id = :c, updated_at = now() where id = :id").param("c", categoryId).param("id", dishId).update();
    }

    @Override
    public void setRestricted(UUID dishId, boolean restricted) {
        jdbc.sql("update menu_items set category_restricted = :r where id = :id").param("r", restricted).param("id", dishId).update();
    }

    @Override
    public Optional<UUID> operatingShopOf(UUID ownerUserId) {
        return vendors.findByOwnerUserId(ownerUserId).filter(v -> v.getStatus() == VendorStatus.APPROVED).map(v -> v.getId());
    }

    @Override
    public Optional<String> shopName(UUID vendorId) {
        return jdbc.sql("select name from vendors where id = :id").param("id", vendorId).query(String.class).optional();
    }

    @Override
    public List<ShopDish> dishesOf(UUID vendorId) {
        return jdbc.sql("select id, name, price, category_id, category_restricted from menu_items where vendor_id = :v and archived_at is null order by name, id").param("v", vendorId)
                .query((rs, n) -> new ShopDish(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("price"), rs.getObject("category_id", UUID.class), rs.getBoolean("category_restricted")))
                .list();
    }
}
