package com.bonbon.backend.marketplace.merchant.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.identity.sellerteam.TeamShops;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The shops seen by the seller team: which shop an account owns, and the names and statuses to show. */
@Component
class TeamShopDirectory implements TeamShops {

    private final JdbcClient jdbc;

    TeamShopDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TeamShop> ofOwner(UUID ownerUserId) {
        return jdbc.sql("select id, owner_user_id, name, status from marketplace.vendors where owner_user_id = :o").param("o", ownerUserId)
                .query((rs, n) -> new TeamShop(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
                .optional();
    }

    @Override
    public Map<UUID, TeamShop> byIds(Collection<UUID> vendorIds) {
        Map<UUID, TeamShop> out = new LinkedHashMap<>();
        if (vendorIds.isEmpty()) {
            return out;
        }
        jdbc.sql("select id, owner_user_id, name, status from marketplace.vendors where id in (:ids) order by name").param("ids", vendorIds)
                .query((rs, n) -> new TeamShop(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
                .list().forEach(s -> out.put(s.vendorId(), s));
        return out;
    }
}
