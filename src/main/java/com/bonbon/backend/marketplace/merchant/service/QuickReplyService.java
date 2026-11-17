package com.bonbon.backend.marketplace.merchant.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.dto.QuickReplyDtos;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop's quick replies (the macros of Intercom and the quick replies of WhatsApp Business): typed in chat as "/" + shortcut. At most
 * 30 a shop, shortcuts unique in the shop. A shop gets three starters the first time it opens the list.
 */
@Service
public class QuickReplyService {

    static final int MAX = 30;
    private static final List<String[]> STARTERS = List.of(
            new String[] {"cam-on", "Cảm ơn bạn đã đặt món! Quán đang chuẩn bị và sẽ giao sớm nhất có thể."},
            new String[] {"giao", "Đơn của bạn đang trên đường giao, bạn để ý điện thoại giúp quán nhé."},
            new String[] {"het-mon", "Xin lỗi bạn, món này vừa hết. Bạn có muốn đổi sang món khác không?"});

    private final JdbcClient jdbc;
    private final ApprovedShops shops;

    QuickReplyService(JdbcClient jdbc, ApprovedShops shops) {
        this.jdbc = jdbc;
        this.shops = shops;
    }

    @Transactional
    public List<QuickReplyDtos.View> list(CurrentPrincipal owner) {
        UUID vendor = shops.of(owner.id()).getId();
        boolean seeded = Boolean.TRUE.equals(jdbc.sql("select quick_replies_seeded from marketplace.vendors where id = :v").param("v", vendor)
                .query(Boolean.class).single());
        if (!seeded) {
            int i = 0;
            for (String[] s : STARTERS) {
                jdbc.sql("""
                        insert into marketplace.quick_replies (vendor_id, shortcut, text, sort_order) values (:v, :s, :t, :o)
                        on conflict do nothing""")
                        .param("v", vendor).param("s", s[0]).param("t", s[1]).param("o", i++).update();
            }
            markSeeded(vendor);
        }
        return read(vendor);
    }

    @Transactional
    public QuickReplyDtos.View create(CurrentPrincipal owner, QuickReplyDtos.Request r) {
        UUID vendor = shops.of(owner.id()).getId();
        if (jdbc.sql("select count(*) from marketplace.quick_replies where vendor_id = :v").param("v", vendor).query(Long.class).single() >= MAX) {
            throw BusinessException.conflict("TOO_MANY_QUICK_REPLIES", "Mỗi quán có tối đa " + MAX + " tin trả lời mẫu.");
        }
        if (taken(vendor, r.shortcut(), null)) {
            throw taken();
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into marketplace.quick_replies (id, vendor_id, shortcut, text, sort_order)
                    values (:id, :v, :s, :t, (select coalesce(max(sort_order), -1) + 1 from marketplace.quick_replies where vendor_id = :v))""")
                    .param("id", id).param("v", vendor).param("s", r.shortcut()).param("t", r.text().strip()).update();
        } catch (DuplicateKeyException e) {
            throw taken();
        }
        markSeeded(vendor);
        return new QuickReplyDtos.View(id, r.shortcut(), r.text().strip());
    }

    @Transactional
    public QuickReplyDtos.View update(CurrentPrincipal owner, UUID id, QuickReplyDtos.Request r) {
        UUID vendor = shops.of(owner.id()).getId();
        if (taken(vendor, r.shortcut(), id)) {
            throw taken();
        }
        int n = jdbc.sql("update marketplace.quick_replies set shortcut = :s, text = :t where id = :id and vendor_id = :v")
                .param("s", r.shortcut()).param("t", r.text().strip()).param("id", id).param("v", vendor).update();
        if (n == 0) {
            throw notFound();
        }
        return new QuickReplyDtos.View(id, r.shortcut(), r.text().strip());
    }

    @Transactional
    public void delete(CurrentPrincipal owner, UUID id) {
        UUID vendor = shops.of(owner.id()).getId();
        if (jdbc.sql("delete from marketplace.quick_replies where id = :id and vendor_id = :v").param("id", id).param("v", vendor).update() == 0) {
            throw notFound();
        }
        markSeeded(vendor);
    }

    private boolean taken(UUID vendor, String shortcut, UUID except) {
        return jdbc.sql("select count(*) from marketplace.quick_replies where vendor_id = :v and shortcut = :s and id <> :x")
                .param("v", vendor).param("s", shortcut).param("x", except == null ? new UUID(0, 0) : except).query(Long.class).single() > 0;
    }

    private void markSeeded(UUID vendor) {
        jdbc.sql("update marketplace.vendors set quick_replies_seeded = true where id = :v").param("v", vendor).update();
    }

    private List<QuickReplyDtos.View> read(UUID vendor) {
        return jdbc.sql("select id, shortcut, text from marketplace.quick_replies where vendor_id = :v order by sort_order, shortcut")
                .param("v", vendor)
                .query((rs, n) -> new QuickReplyDtos.View(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).list();
    }

    private static BusinessException taken() {
        return BusinessException.conflict("QUICK_REPLY_SHORTCUT_TAKEN", "Mã gõ tắt này đã dùng cho tin khác.");
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("QUICK_REPLY_NOT_FOUND", "Không tìm thấy tin trả lời mẫu.");
    }
}
