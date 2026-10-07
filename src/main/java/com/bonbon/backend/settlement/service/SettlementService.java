package com.bonbon.backend.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopNames;
import com.bonbon.backend.order.OrderMoney;
import com.bonbon.backend.settlement.PayoutRecorded;
import com.bonbon.backend.settlement.dto.SettlementRequests;
import com.bonbon.backend.settlement.dto.SettlementViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the administrator sees and does about money between the platform and the shops (flows/settlement/): the
 * overview, one shop's ledger and statements, and recording a payout, a collection or an adjustment. Everything is
 * computed from {@code ledger_entries}, so every screen ties out by construction.
 */
@Service
public class SettlementService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ORDER_TYPES = "('ONLINE_EARNING', 'COD_COMMISSION')";
    static final String LOW_RATE_RATIO_KEY = "settlement.low_rate_ratio";
    static final String LOW_RATE_MIN_ORDERS_KEY = "settlement.low_rate_min_orders";

    private final JdbcClient jdbc;
    private final ShopNames shopNames;
    private final OrderMoney orders;
    private final CommissionRateService commission;
    private final SystemSettingsService settings;
    private final ApplicationEventPublisher events;

    SettlementService(JdbcClient jdbc, ShopNames shopNames, OrderMoney orders, CommissionRateService commission, SystemSettingsService settings,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.shopNames = shopNames;
        this.orders = orders;
        this.commission = commission;
        this.settings = settings;
        this.events = events;
    }

    // --- the overview

    /** {@code status}: null for every shop, {@code OWED_TO_SHOP} (balance above 0) or {@code OWED_BY_SHOP} (below 0). */
    @Transactional(readOnly = true)
    public SettlementViews.Overview overview(String status, String sort, int page, int size) {
        checkPage(page, size);
        String having = switch (status == null ? "" : status) {
            case "" -> "";
            case "OWED_TO_SHOP" -> " having sum(amount) > 0";
            case "OWED_BY_SHOP" -> " having sum(amount) < 0";
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "`status` chỉ nhận OWED_TO_SHOP hoặc OWED_BY_SHOP.");
        };
        String order = switch (sort == null ? "balance_desc" : sort) {
            case "balance_desc" -> "balance desc, vendor_id";
            case "balance_asc" -> "balance asc, vendor_id";
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "`sort` chỉ nhận balance_desc hoặc balance_asc.");
        };
        String perShop = """
                select vendor_id, sum(amount) as balance,
                       count(*) filter (where type in %1$s) as orders,
                       coalesce(sum(items_total - discount) filter (where type in %1$s), 0) as food,
                       coalesce(sum(commission) filter (where type in %1$s), 0) as commission,
                       max(created_at) filter (where type = 'PAYOUT') as last_payout
                from ledger_entries group by vendor_id""".formatted(ORDER_TYPES);

        Totals totals = jdbc.sql("""
                select count(*) as shops, coalesce(sum(orders), 0) as orders, coalesce(sum(food), 0) as food, coalesce(sum(commission), 0) as commission,
                       coalesce(sum(greatest(balance, 0)), 0) as owed_to, coalesce(sum(greatest(-balance, 0)), 0) as owed_by
                from (""" + perShop + ") t")
                .query((rs, n) -> new Totals(rs.getLong("shops"), rs.getLong("orders"), rs.getLong("food"), rs.getLong("commission"),
                        rs.getLong("owed_to"), rs.getLong("owed_by"))).single();
        BigDecimal average = rate(totals.commission(), totals.food());
        long net = commission.split((int) totals.commission()).net();

        long total = jdbc.sql("select count(*) from (" + perShop + having + ") t").query(Long.class).single();
        List<Raw> rows = jdbc.sql("select * from (" + perShop + having + ") t order by " + order + " limit :limit offset :offset")
                .param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Raw(rs.getObject("vendor_id", UUID.class), rs.getLong("balance"), rs.getLong("orders"), rs.getLong("food"),
                        rs.getLong("commission"), rs.getTimestamp("last_payout") == null ? null : rs.getTimestamp("last_payout").toInstant())).list();
        Map<UUID, String> names = shopNames.names(rows.stream().map(Raw::vendorId).toList());
        BigDecimal ratio = new BigDecimal(settings.getString(LOW_RATE_RATIO_KEY, "0.5"));
        long minOrders = settings.getLong(LOW_RATE_MIN_ORDERS_KEY, 5);
        List<SettlementViews.ShopRow> items = new ArrayList<>();
        for (Raw r : rows) {
            BigDecimal effective = rate(r.commission(), r.food());
            boolean low = effective != null && average != null && r.orders() >= minOrders
                    && effective.compareTo(average.multiply(ratio)) < 0;
            items.add(new SettlementViews.ShopRow(r.vendorId(), names.getOrDefault(r.vendorId(), "(đã xoá)"), r.balance(), payable(r.balance()),
                    Math.max(-r.balance(), 0), r.orders(), r.food(), r.commission(), effective, low, r.lastPayout()));
        }
        SettlementViews.Totals view = new SettlementViews.Totals(totals.shops(), totals.orders(), totals.food(), totals.commission(), net,
                totals.commission() - net, average, totals.owedTo(), totals.owedBy());
        return new SettlementViews.Overview(view, new SettlementViews.ShopPage(items, page, size, total));
    }

    private record Totals(long shops, long orders, long food, long commission, long owedTo, long owedBy) {
    }

    private record Raw(UUID vendorId, long balance, long orders, long food, long commission, java.time.Instant lastPayout) {
    }

    // --- one shop

    @Transactional(readOnly = true)
    public SettlementViews.Vendor vendor(UUID vendorId) {
        String name = nameOf(vendorId);
        long balance = balance(vendorId);
        return new SettlementViews.Vendor(vendorId, name, balance, payable(balance), heldForCases(vendorId), Math.max(-balance, 0));
    }

    /** Newest first; {@code type} narrows to one kind of entry. */
    @Transactional(readOnly = true)
    public SettlementViews.LedgerPage ledger(UUID vendorId, String type, int page, int size) {
        checkPage(page, size);
        nameOf(vendorId);
        if (type != null && !Set.of("ONLINE_EARNING", "COD_COMMISSION", "PAYOUT", "COLLECTION", "ADJUSTMENT", "CASE_REFUND",
                "CASE_COMMISSION_REVERSAL", "TAX_WITHHOLDING").contains(type)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TYPE", "Loại bút toán không hợp lệ.");
        }
        String where = type == null ? "" : " and type = :type";
        var count = jdbc.sql("select count(*) from ledger_entries where vendor_id = :v" + where).param("v", vendorId);
        var list = jdbc.sql("""
                select id, type, amount, order_id, reference, note, acted_by_type, acted_by_id, created_at
                from ledger_entries where vendor_id = :v""" + where + " order by created_at desc, id limit :limit offset :offset")
                .param("v", vendorId).param("limit", size).param("offset", (long) page * size);
        if (type != null) {
            count = count.param("type", type);
            list = list.param("type", type);
        }
        long total = count.query(Long.class).single();
        List<SettlementViews.Entry> raw = list.query((rs, n) -> new SettlementViews.Entry(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getInt("amount"), rs.getObject("order_id", UUID.class), null, rs.getString("reference"), rs.getString("note"),
                rs.getString("acted_by_type"), rs.getObject("acted_by_id", UUID.class), rs.getTimestamp("created_at").toInstant())).list();
        Map<UUID, Long> numbers = orders.numbers(raw.stream().map(SettlementViews.Entry::orderId).filter(java.util.Objects::nonNull).toList());
        List<SettlementViews.Entry> items = raw.stream().map(e -> e.orderId() == null ? e : new SettlementViews.Entry(e.id(), e.type(), e.amount(),
                e.orderId(), numbers.get(e.orderId()), e.reference(), e.note(), e.actedByType(), e.actedById(), e.createdAt())).toList();
        return new SettlementViews.LedgerPage(items, page, size, total, balance(vendorId));
    }

    /**
     * Figures per day, week (from Monday) or month in Vietnam time, from the ledger alone. {@code to} is inclusive. Periods
     * with no entry are left out; the closing balance runs on from the balance before {@code from}.
     */
    @Transactional(readOnly = true)
    public SettlementViews.Statement statement(UUID vendorId, LocalDate from, LocalDate to, String granularity) {
        nameOf(vendorId);
        return statementOf(vendorId, from, to, granularity);
    }

    /** The same statement for a caller that has already proved the shop is theirs. */
    @Transactional(readOnly = true)
    public SettlementViews.Statement statementOf(UUID vendorId, LocalDate from, LocalDate to, String granularity) {
        String g = granularity == null ? "week" : granularity;
        if (!Set.of("day", "week", "month").contains(g)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_GRANULARITY", "`granularity` chỉ nhận day, week hoặc month.");
        }
        LocalDate end = to == null ? LocalDate.now(VIETNAM) : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end) || start.plusYears(2).isBefore(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "Khoảng thời gian không hợp lệ (tối đa 2 năm, `from` không sau `to`).");
        }
        Timestamp lower = Timestamp.from(start.atStartOfDay(VIETNAM).toInstant());
        Timestamp upper = Timestamp.from(end.plusDays(1).atStartOfDay(VIETNAM).toInstant());
        long opening = jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v and created_at < :lower")
                .param("v", vendorId).param("lower", lower).query(Long.class).single();
        List<PeriodRow> rows = jdbc.sql("""
                select date_trunc(:g, created_at at time zone 'Asia/Ho_Chi_Minh')::date as start,
                       count(*) filter (where type in %1$s) as orders,
                       count(*) filter (where type = 'ONLINE_EARNING') as online_orders,
                       count(*) filter (where type = 'COD_COMMISSION') as cash_orders,
                       coalesce(sum(items_total) filter (where type in %1$s), 0) as food,
                       coalesce(sum(discount) filter (where type in %1$s), 0) as discounts,
                       coalesce(sum(delivery_fee) filter (where type in %1$s), 0) as fees,
                       coalesce(sum(commission) filter (where type in %1$s), 0) as commission,
                       coalesce(-sum(amount) filter (where type = 'PAYOUT'), 0) as payouts,
                       coalesce(sum(amount) filter (where type = 'COLLECTION'), 0) as collections,
                       coalesce(sum(amount) filter (where type = 'ADJUSTMENT'), 0) as adjustments,
                       coalesce(sum(amount) filter (where type in ('CASE_REFUND', 'CASE_COMMISSION_REVERSAL', 'TAX_WITHHOLDING')), 0) as other,
                       sum(amount) as change
                from ledger_entries where vendor_id = :v and created_at >= :lower and created_at < :upper
                group by 1 order by 1""".formatted(ORDER_TYPES))
                .param("g", g).param("v", vendorId).param("lower", lower).param("upper", upper)
                .query((rs, n) -> new PeriodRow(rs.getDate("start").toLocalDate(), rs.getLong("orders"), rs.getLong("online_orders"),
                        rs.getLong("cash_orders"), rs.getLong("food"), rs.getLong("discounts"), rs.getLong("fees"), rs.getLong("commission"),
                        rs.getLong("payouts"), rs.getLong("collections"), rs.getLong("adjustments"), rs.getLong("other"), rs.getLong("change")))
                .list();
        List<SettlementViews.Period> periods = new ArrayList<>();
        long running = opening;
        for (PeriodRow r : rows) {
            running += r.change();
            CommissionRateService.Split split = commission.split((int) r.commission());
            periods.add(new SettlementViews.Period(r.start(), r.orders(), r.online(), r.cash(), r.food(), r.discounts(), r.fees(), r.commission(),
                    split.net(), split.vat(), r.payouts(), r.collections(), r.adjustments(), r.other(), r.change(), running));
        }
        return new SettlementViews.Statement(g, opening, running, periods);
    }

    private record PeriodRow(LocalDate start, long orders, long online, long cash, long food, long discounts, long fees, long commission,
            long payouts, long collections, long adjustments, long other, long change) {
    }

    // --- recording money moved outside the system

    /** The entry and whether this call created it (a repeat of the same request returns the first one). */
    public record Recorded(SettlementViews.Entry entry, boolean created) {
    }

    @Transactional
    public Recorded record(CurrentPrincipal admin, UUID vendorId, String idempotencyKey, SettlementRequests.Entry request) {
        nameOf(vendorId);
        long amount = request.amount();
        String reference = blankToNull(request.reference());
        String note = blankToNull(request.note());
        int signed;
        switch (request.type()) {
            case "PAYOUT", "COLLECTION" -> {
                if (amount <= 0 || amount > Integer.MAX_VALUE) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID", "Số tiền phải là số dương.");
                }
                if (reference == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REFERENCE_REQUIRED", "Cần mã giao dịch ngân hàng để đối chiếu sao kê.");
                }
                signed = (int) ("PAYOUT".equals(request.type()) ? -amount : amount);
            }
            default -> {
                if (amount == 0 || Math.abs(amount) > Integer.MAX_VALUE) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID", "Số tiền điều chỉnh phải khác 0.");
                }
                if (note == null) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Điều chỉnh phải có lý do.");
                }
                signed = (int) amount;
            }
        }
        // One at a time per shop: the balance read below must still be true when the entry is written.
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:v, 0))").param("v", vendorId.toString()).query().singleRow();
        Optional<SettlementViews.Entry> earlier = existing(admin.id(), idempotencyKey);
        if (earlier.isPresent()) {
            SettlementViews.Entry e = earlier.get();
            UUID sameVendor = jdbc.sql("select vendor_id from ledger_entries where id = :id").param("id", e.id()).query(UUID.class).single();
            if (!sameVendor.equals(vendorId) || !e.type().equals(request.type()) || e.amount() != signed) {
                throw BusinessException.conflict("IDEMPOTENCY_KEY_REUSED", "Khoá này đã dùng cho một khoản khác.");
            }
            return new Recorded(e, false);
        }
        long balance = balance(vendorId);
        if ("PAYOUT".equals(request.type())) {
            long payable = payable(balance);
            if (amount > payable) {
                throw BusinessException.conflict("PAYOUT_EXCEEDS_PAYABLE", "Số tiền chi trả vượt quá số có thể trả cho quán.")
                        .withProperty("balance", balance).withProperty("heldForCases", heldForCases(vendorId)).withProperty("payable", payable);
            }
        } else if ("COLLECTION".equals(request.type())) {
            long owed = Math.max(-balance, 0);
            if (owed == 0) {
                throw BusinessException.conflict("NOTHING_OWED", "Quán này không nợ nền tảng khoản nào.").withProperty("balance", balance);
            }
            if (amount > owed) {
                throw BusinessException.conflict("COLLECTION_EXCEEDS_DEBT", "Số tiền thu vượt quá số quán đang nợ.").withProperty("owed", owed);
            }
        }
        UUID id = jdbc.sql("""
                insert into ledger_entries (vendor_id, type, amount, reference, note, acted_by_type, acted_by_id, idempotency_key)
                values (:v, :type, :amount, :ref, :note, :by, :actor, :key) returning id""")
                .param("v", vendorId).param("type", request.type()).param("amount", signed).param("ref", reference).param("note", note)
                .param("by", admin.actorType().name()).param("actor", admin.id()).param("key", idempotencyKey).query(UUID.class).single();
        if ("PAYOUT".equals(request.type())) {
            events.publishEvent(new PayoutRecorded(vendorId, (int) amount, reference));
        }
        return new Recorded(existing(admin.id(), idempotencyKey).orElseThrow(), true);
    }

    // --- internals

    /** Money set aside for undecided order cases; order cases do not exist yet, so nothing is held. */
    long heldForCases(UUID vendorId) {
        return 0;
    }

    long payable(long balance) {
        return Math.max(balance, 0);
    }

    private long balance(UUID vendorId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    private String nameOf(UUID vendorId) {
        String name = shopNames.names(List.of(vendorId)).get(vendorId);
        if (name == null) {
            throw BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán.");
        }
        return name;
    }

    private Optional<SettlementViews.Entry> existing(UUID adminId, String key) {
        return jdbc.sql("""
                select id, type, amount, order_id, reference, note, acted_by_type, acted_by_id, created_at
                from ledger_entries where acted_by_id = :admin and idempotency_key = :key""")
                .param("admin", adminId).param("key", key)
                .query((rs, n) -> new SettlementViews.Entry(rs.getObject("id", UUID.class), rs.getString("type"), rs.getInt("amount"),
                        rs.getObject("order_id", UUID.class), null, rs.getString("reference"), rs.getString("note"), rs.getString("acted_by_type"),
                        rs.getObject("acted_by_id", UUID.class), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private static BigDecimal rate(long commission, long food) {
        return food <= 0 ? null : new BigDecimal(commission).multiply(new BigDecimal(100)).divide(new BigDecimal(food), 2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
    }
}
