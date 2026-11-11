package com.bonbon.backend.ordering.order.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's own say over the home page (owner decision 2026-11-03): favourite shops (the heart) and shops hidden for 30 days
 * ("Ẩn quán này"). Both are plain rows keyed by user and shop id; the shop itself stays in the marketplace module.
 */
@Service
public class ShopPreferenceService {

    public static final Duration HIDE_FOR = Duration.ofDays(30);
    static final int MAX_FAVORITES = 200;

    private final JdbcClient jdbc;
    private final ShopCatalog shops;

    ShopPreferenceService(JdbcClient jdbc, ShopCatalog shops) {
        this.jdbc = jdbc;
        this.shops = shops;
    }

    @Transactional
    public void addFavorite(UUID userId, UUID shopId) {
        requireShop(shopId);
        int count = jdbc.sql("select count(*) from favorite_shops where user_id = :u").param("u", userId).query(Integer.class).single();
        if (count >= MAX_FAVORITES) {
            throw BusinessException.conflict("FAVORITES_FULL", "Bạn đã có " + MAX_FAVORITES + " quán yêu thích. Hãy bỏ bớt quán cũ.");
        }
        jdbc.sql("insert into favorite_shops (user_id, vendor_id) values (:u, :v) on conflict do nothing")
                .param("u", userId).param("v", shopId).update();
    }

    @Transactional
    public void removeFavorite(UUID userId, UUID shopId) {
        jdbc.sql("delete from favorite_shops where user_id = :u and vendor_id = :v").param("u", userId).param("v", shopId).update();
    }

    /** Newest favourite first. */
    @Transactional(readOnly = true)
    public List<UUID> favoriteIds(UUID userId) {
        return jdbc.sql("select vendor_id from favorite_shops where user_id = :u order by created_at desc")
                .param("u", userId).query(UUID.class).list();
    }

    /** Hidden for 30 days from now; hiding it again restarts the 30 days. A hidden favourite stays a favourite. */
    @Transactional
    public Instant hide(UUID userId, UUID shopId, Instant now) {
        requireShop(shopId);
        Instant until = now.plus(HIDE_FOR);
        jdbc.sql("""
                insert into hidden_shops (user_id, vendor_id, hidden_until) values (:u, :v, :until)
                on conflict (user_id, vendor_id) do update set hidden_until = excluded.hidden_until""")
                .param("u", userId).param("v", shopId).param("until", Timestamp.from(until)).update();
        return until;
    }

    @Transactional
    public void unhide(UUID userId, UUID shopId) {
        jdbc.sql("delete from hidden_shops where user_id = :u and vendor_id = :v").param("u", userId).param("v", shopId).update();
    }

    /** The shops this customer has hidden and whose 30 days are not over. */
    @Transactional(readOnly = true)
    public Set<UUID> hiddenIds(UUID userId, Instant now) {
        return jdbc.sql("select vendor_id from hidden_shops where user_id = :u and hidden_until > :now")
                .param("u", userId).param("now", Timestamp.from(now)).query(UUID.class).list().stream().collect(Collectors.toSet());
    }

    private void requireShop(UUID shopId) {
        if (shops.feedCards(List.of(shopId), null, null).isEmpty()) {
            throw BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán.");
        }
    }
}
