package com.bonbon.backend.settlement.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.OrderMoney;
import com.bonbon.backend.settlement.dto.EarningsViews;
import com.bonbon.backend.settlement.dto.SettlementViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop reading its own money (flows/settlement/view-earnings.md). It works out which shop the caller acts for and
 * then reads the same ledger the administrator reads, so the two always tie out; nothing here looks at another shop.
 */
@Service
public class EarningsService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> TYPES = Set.of("ONLINE_EARNING", "COD_COMMISSION", "PAYOUT", "COLLECTION", "ADJUSTMENT", "CASE_REFUND",
            "CASE_COMMISSION_REVERSAL", "TAX_WITHHOLDING");

    private final ShopOrdering shops;
    private final JdbcClient jdbc;
    private final OrderMoney orders;
    private final SettlementService settlement;

    EarningsService(ShopOrdering shops, JdbcClient jdbc, OrderMoney orders, SettlementService settlement) {
        this.shops = shops;
        this.jdbc = jdbc;
        this.orders = orders;
        this.settlement = settlement;
    }

    @Transactional(readOnly = true)
    public EarningsViews.Summary summary(CurrentPrincipal caller) {
        UUID vendor = vendorOf(caller);
        SettlementViews.Vendor v = settlement.vendor(vendor);
        var last = jdbc.sql("select -amount as amount, created_at from ledger_entries where vendor_id = :v and type = 'PAYOUT' order by created_at desc, id limit 1")
                .param("v", vendor).query((rs, n) -> Map.entry(rs.getLong("amount"), rs.getTimestamp("created_at").toInstant())).optional();
        return new EarningsViews.Summary(v.balance(), v.payable(), v.heldForCases(), v.owed(), last.map(Map.Entry::getKey).orElse(null),
                last.map(Map.Entry::getValue).orElse(null));
    }

    @Transactional(readOnly = true)
    public SettlementViews.Statement statement(CurrentPrincipal caller, LocalDate from, LocalDate to, String granularity) {
        return settlement.statementOf(vendorOf(caller), from, to, granularity);
    }

    /** Newest first; {@code type} and an inclusive Vietnam-time date range narrow it, which is how an order figure is drilled into. */
    @Transactional(readOnly = true)
    public EarningsViews.Ledger ledger(CurrentPrincipal caller, String type, LocalDate from, LocalDate to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (type != null && !TYPES.contains(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "Loại bút toán không hợp lệ.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "`from` không được sau `to`.");
        }
        UUID vendor = vendorOf(caller);
        StringBuilder where = new StringBuilder(" where vendor_id = :v");
        if (type != null) {
            where.append(" and type = :type");
        }
        if (from != null) {
            where.append(" and created_at >= :from");
        }
        if (to != null) {
            where.append(" and created_at < :to");
        }
        var count = jdbc.sql("select count(*) from ledger_entries" + where);
        var list = jdbc.sql("""
                select id, type, amount, order_id, items_total, discount, delivery_fee, commission, reference, note, acted_by_type, created_at
                from ledger_entries""" + where + " order by created_at desc, id limit :limit offset :offset");
        count = count.param("v", vendor);
        list = list.param("v", vendor).param("limit", size).param("offset", (long) page * size);
        if (type != null) {
            count = count.param("type", type);
            list = list.param("type", type);
        }
        if (from != null) {
            Timestamp lower = Timestamp.from(from.atStartOfDay(VIETNAM).toInstant());
            count = count.param("from", lower);
            list = list.param("from", lower);
        }
        if (to != null) {
            Timestamp upper = Timestamp.from(to.plusDays(1).atStartOfDay(VIETNAM).toInstant());
            count = count.param("to", upper);
            list = list.param("to", upper);
        }
        long total = count.query(Long.class).single();
        List<EarningsViews.Entry> raw = list.query((rs, n) -> new EarningsViews.Entry(rs.getObject("id", UUID.class), rs.getString("type"), rs.getInt("amount"),
                rs.getObject("order_id", UUID.class), null, (Integer) rs.getObject("items_total"), (Integer) rs.getObject("discount"),
                (Integer) rs.getObject("delivery_fee"), (Integer) rs.getObject("commission"), rs.getString("reference"), rs.getString("note"),
                "SYSTEM".equals(rs.getString("acted_by_type")) ? "SYSTEM" : "PLATFORM", rs.getTimestamp("created_at").toInstant())).list();
        Map<UUID, Long> numbers = orders.numbers(raw.stream().map(EarningsViews.Entry::orderId).filter(Objects::nonNull).toList());
        List<EarningsViews.Entry> items = raw.stream().map(e -> e.orderId() == null ? e : new EarningsViews.Entry(e.id(), e.type(), e.amount(), e.orderId(),
                numbers.get(e.orderId()), e.itemsTotal(), e.discount(), e.deliveryFee(), e.commission(), e.reference(), e.note(), e.source(), e.createdAt())).toList();
        return new EarningsViews.Ledger(items, page, size, total);
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.approvedVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }
}
