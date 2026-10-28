package com.bonbon.backend.insights.platformstatistics.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.insights.platformstatistics.dto.PlatformStatsViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-wide figures (flows/platform-statistics/). Dates are Vietnam-time days with both ends inclusive; only
 * {@code DELIVERED} orders count, at the moment they were delivered, exactly as in a shop's own report, so the two tie out.
 * Every period of the range comes back, empty ones as zero.
 */
@Service
public class PlatformStatisticsService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_BUCKETS = 400;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SORTS = Map.of("revenue", "revenue desc", "orders", "orders desc", "commission", "commission desc", "name", "name asc");

    private final JdbcClient jdbc;
    private final Clock clock;

    PlatformStatisticsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PlatformStatsViews.Overview overview(LocalDate from, LocalDate to, String granularity) {
        String g = granularity(granularity);
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        List<LocalDate> periods = periods(start, end, g);
        Timestamp lo = Timestamp.from(startOf(start));
        Timestamp hi = Timestamp.from(startOf(end.plusDays(1)));

        Map<LocalDate, long[]> found = new HashMap<>();
        jdbc.sql("""
                select date_trunc(:g, finished_at at time zone 'Asia/Ho_Chi_Minh')::date as start, count(*) as orders, sum(grand_total) as revenue, sum(commission_amount) as commission
                from orders where status = 'DELIVERED' and finished_at >= :from and finished_at < :to group by 1""")
                .param("g", g).param("from", lo).param("to", hi)
                .query((rs, n) -> found.put(rs.getDate("start").toLocalDate(), new long[] { rs.getLong("orders"), rs.getLong("revenue"), rs.getLong("commission") })).list();
        List<PlatformStatsViews.Bucket> buckets = new ArrayList<>();
        long orders = 0;
        long revenue = 0;
        long commission = 0;
        for (LocalDate p : periods) {
            long[] v = found.getOrDefault(p, new long[3]);
            orders += v[0];
            revenue += v[1];
            commission += v[2];
            buckets.add(new PlatformStatsViews.Bucket(p, v[0], v[1], v[2]));
        }
        long[] active = jdbc.sql("select count(distinct vendor_id) as shops, count(distinct customer_id) as customers from orders where status = 'DELIVERED' and finished_at >= :from and finished_at < :to")
                .param("from", lo).param("to", hi).query((rs, n) -> new long[] { rs.getLong("shops"), rs.getLong("customers") }).single();
        return new PlatformStatsViews.Overview(start, end, g,
                new PlatformStatsViews.Totals(orders, revenue, commission, active[0], active[1], active[0]), buckets);
    }

    @Transactional(readOnly = true)
    public PlatformStatsViews.MerchantPage revenueByMerchant(LocalDate from, LocalDate to, String sort, String q, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String order = SORTS.get(sort == null ? "revenue" : sort);
        if (order == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "`sort` chỉ nhận revenue, orders, commission hoặc name.");
        }
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        checkRange(start, end);
        Timestamp lo = Timestamp.from(startOf(start));
        Timestamp hi = Timestamp.from(startOf(end.plusDays(1)));
        boolean search = q != null && !q.isBlank();
        String filter = search ? " and unaccent(lower(v.name)) like unaccent(:pattern)" : "";
        String pattern = search ? "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" : null;
        String base = """
                from orders o join vendors v on v.id = o.vendor_id
                where o.status = 'DELIVERED' and o.finished_at >= :from and o.finished_at < :to""" + filter + " group by v.id, v.name";
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from (select v.id " + base + ") t").param("from", lo).param("to", hi);
        JdbcClient.StatementSpec rows = jdbc.sql("select v.id, v.name, count(*) as orders, sum(o.grand_total) as revenue, sum(o.commission_amount) as commission " + base
                + " order by " + order + ", v.id limit :limit offset :offset").param("from", lo).param("to", hi).param("limit", size).param("offset", (long) page * size);
        if (search) {
            count = count.param("pattern", pattern);
            rows = rows.param("pattern", pattern);
        }
        long total = count.query(Long.class).single();
        List<PlatformStatsViews.MerchantRow> items = rows.query((rs, n) -> new PlatformStatsViews.MerchantRow(rs.getObject("id", java.util.UUID.class), rs.getString("name"),
                rs.getLong("orders"), rs.getLong("revenue"), rs.getLong("commission"))).list();
        return new PlatformStatsViews.MerchantPage(start, end, items, page, size, total);
    }

    @Transactional(readOnly = true)
    public PlatformStatsViews.UserGrowth userGrowth(LocalDate from, LocalDate to, String granularity) {
        String g = granularity(granularity);
        LocalDate end = to == null ? today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        List<LocalDate> periods = periods(start, end, g);
        Map<LocalDate, long[]> found = new HashMap<>();
        jdbc.sql("""
                select date_trunc(:g, created_at at time zone 'Asia/Ho_Chi_Minh')::date as start, role, count(*) as n
                from user_roles where role in ('CUSTOMER', 'SELLER') and created_at >= :from and created_at < :to group by 1, 2""")
                .param("g", g).param("from", Timestamp.from(startOf(start))).param("to", Timestamp.from(startOf(end.plusDays(1))))
                .query((rs, i) -> {
                    long[] v = found.computeIfAbsent(rs.getDate("start").toLocalDate(), k -> new long[2]);
                    v["CUSTOMER".equals(rs.getString("role")) ? 0 : 1] = rs.getLong("n");
                    return 0;
                }).list();
        List<PlatformStatsViews.GrowthBucket> buckets = new ArrayList<>();
        long customers = 0;
        long sellers = 0;
        for (LocalDate p : periods) {
            long[] v = found.getOrDefault(p, new long[2]);
            customers += v[0];
            sellers += v[1];
            buckets.add(new PlatformStatsViews.GrowthBucket(p, v[0], v[1]));
        }
        return new PlatformStatsViews.UserGrowth(start, end, g, customers, sellers, buckets);
    }

    // --- internals

    private static String granularity(String granularity) {
        String g = granularity == null ? "day" : granularity;
        if (!Set.of("day", "week", "month").contains(g)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_GRANULARITY", "`granularity` chỉ nhận day, week hoặc month.");
        }
        return g;
    }

    private static void checkRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "`from` không được sau `to`.");
        }
    }

    private List<LocalDate> periods(LocalDate from, LocalDate to, String granularity) {
        checkRange(from, to);
        LocalDate first = switch (granularity) {
            case "week" -> from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> from.withDayOfMonth(1);
            default -> from;
        };
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate p = first; !p.isAfter(to); p = switch (granularity) {
            case "week" -> p.plusWeeks(1);
            case "month" -> p.plusMonths(1);
            default -> p.plusDays(1);
        }) {
            result.add(p);
            if (result.size() > MAX_BUCKETS) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LONG", "Khoảng thời gian quá dài cho cách chia này; hãy chia theo tuần hoặc tháng.");
            }
        }
        return result;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(VIETNAM));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(VIETNAM).toInstant();
    }
}
