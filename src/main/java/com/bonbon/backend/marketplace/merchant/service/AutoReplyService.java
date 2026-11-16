package com.bonbon.backend.marketplace.merchant.service;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.ShopAutoReplies;
import com.bonbon.backend.marketplace.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.marketplace.merchant.entity.Vendor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop's one auto-reply (configure-auto-reply.md): on by default with a starter message, editable and switchable by the shop. The
 * columns live on {@code vendors} and are read and written here only.
 */
@Service
public class AutoReplyService implements ShopAutoReplies {

    public static final int MAX_LENGTH = 1000;

    private final JdbcClient jdbc;
    private final ApprovedShops shops;

    AutoReplyService(JdbcClient jdbc, ApprovedShops shops) {
        this.jdbc = jdbc;
        this.shops = shops;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> autoReplyOf(UUID vendorId) {
        return jdbc.sql("select auto_reply_message from marketplace.vendors where id = :id and auto_reply_enabled")
                .param("id", vendorId).query(String.class).optional();
    }

    @Transactional(readOnly = true)
    public ShopProfileRequests.AutoReply get(CurrentPrincipal owner) {
        return read(shops.of(owner.id()));
    }

    /** Only what is sent changes; turning it on needs a message, which a shop always has unless it sends a blank one. */
    @Transactional
    public ShopProfileRequests.AutoReply update(CurrentPrincipal owner, ShopProfileRequests.AutoReply request) {
        Vendor vendor = shops.of(owner.id());
        String message = request.message() == null ? null : request.message().strip();
        if (message != null && message.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AUTO_REPLY_EMPTY", "Tin trả lời tự động không được để trống.");
        }
        jdbc.sql("""
                update marketplace.vendors set auto_reply_enabled = coalesce(:enabled, auto_reply_enabled),
                       auto_reply_message = coalesce(:message, auto_reply_message) where id = :id""")
                .param("enabled", request.enabled()).param("message", message).param("id", vendor.getId()).update();
        return read(vendor);
    }

    private ShopProfileRequests.AutoReply read(Vendor vendor) {
        return jdbc.sql("select auto_reply_enabled, auto_reply_message from marketplace.vendors where id = :id").param("id", vendor.getId())
                .query((rs, n) -> new ShopProfileRequests.AutoReply(rs.getBoolean(1), rs.getString(2))).single();
    }
}
