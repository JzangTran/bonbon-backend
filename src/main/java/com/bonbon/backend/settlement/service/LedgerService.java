package com.bonbon.backend.settlement.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-shop ledger (flows/settlement/README.md). Entries are appended and never changed (the database refuses an
 * update or a delete); a shop's balance is the sum of its entries. Positive: the platform owes the shop. Negative: the
 * shop owes the platform.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;

    LedgerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID id, UUID vendorId, String type, int amount, UUID orderId, String reference, String note, String actedByType,
            UUID actedById, Instant createdAt) {
    }

    /**
     * Posts an entry tied to an order, once: a repeat of the same order and type changes nothing. Runs in the caller's
     * transaction, so the entry stands or falls with the status change that causes it.
     *
     * @return true when this call wrote the entry
     */
    @Transactional
    public boolean postForOrder(UUID vendorId, String type, int amount, UUID orderId, OrderFigures figures, ActorType by, UUID actorId) {
        return jdbc.sql("""
                insert into ledger_entries (vendor_id, type, amount, order_id, items_total, discount, delivery_fee, commission, acted_by_type, acted_by_id)
                values (:vendor, :type, :amount, :order, :items, :discount, :fee, :commission, :by, :actor)
                on conflict (order_id, type) where order_id is not null and case_id is null do nothing""")
                .param("vendor", vendorId).param("type", type).param("amount", amount).param("order", orderId)
                .param("items", figures.itemsTotal()).param("discount", figures.discount()).param("fee", figures.deliveryFee())
                .param("commission", figures.commission()).param("by", by.name()).param("actor", actorId).update() > 0;
    }

    /** The order figures an entry was posted from, kept on it so statements never need the order tables. */
    public record OrderFigures(int itemsTotal, int discount, int deliveryFee, int commission) {
    }

    /** What the platform owes the shop (positive) or the shop owes the platform (negative). */
    @Transactional(readOnly = true)
    public long balanceOf(UUID vendorId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<Entry> entries(UUID vendorId, int page, int size) {
        return jdbc.sql("""
                select id, vendor_id, type, amount, order_id, reference, note, acted_by_type, acted_by_id, created_at
                from ledger_entries where vendor_id = :v order by created_at desc, id limit :limit offset :offset""")
                .param("v", vendorId).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getString("type"),
                        rs.getInt("amount"), rs.getObject("order_id", UUID.class), rs.getString("reference"), rs.getString("note"),
                        rs.getString("acted_by_type"), rs.getObject("acted_by_id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                .list();
    }
}
