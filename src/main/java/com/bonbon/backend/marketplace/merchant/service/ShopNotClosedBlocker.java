package com.bonbon.backend.marketplace.merchant.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.account.DeletionBlocker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** An owner cannot delete the account while the shop exists: it must be closed, or never opened (draft or rejected). */
@Component
class ShopNotClosedBlocker implements DeletionBlocker {

    private final JdbcClient jdbc;

    ShopNotClosedBlocker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Blocker> blockersOf(UUID userId) {
        long count = jdbc.sql("select count(*) from vendors where owner_user_id = :u and status not in ('DRAFT', 'REJECTED', 'CLOSED')").param("u", userId).query(Long.class).single();
        return count == 0 ? List.of() : List.of(new Blocker("SHOP_NOT_CLOSED", "Cửa hàng của bạn chưa đóng. Hãy đóng cửa hàng và đợi hoàn tất trước khi xoá tài khoản.", "CLOSE_SHOP", count));
    }
}
