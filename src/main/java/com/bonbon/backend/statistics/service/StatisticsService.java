package com.bonbon.backend.statistics.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.OrderSales;
import com.bonbon.backend.statistics.dto.StatsViews;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales statistics of the caller's own shop (flows/statistics/). Dates are Vietnam-time days and both ends are
 * inclusive. Revenue comes back with every period of the range, empty ones as zero, so a chart needs no gap filling.
 */
@Service
public class StatisticsService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int MAX_BUCKETS = 400;
    private static final int MAX_LIMIT = 50;

    private final ShopOrdering shops;
    private final OrderSales sales;

    StatisticsService(ShopOrdering shops, OrderSales sales) {
        this.shops = shops;
        this.sales = sales;
    }

    @Transactional(readOnly = true)
    public StatsViews.Revenue revenue(CurrentPrincipal caller, LocalDate from, LocalDate to, String granularity) {
        String g = granularity == null ? "day" : granularity;
        if (!Set.of("day", "week", "month").contains(g)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_GRANULARITY", "`granularity` chỉ nhận day, week hoặc month.");
        }
        LocalDate end = to == null ? LocalDate.now(VIETNAM) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "`from` không được sau `to`.");
        }
        List<LocalDate> periods = periods(start, end, g);
        if (periods.size() > MAX_BUCKETS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LONG", "Khoảng thời gian quá dài cho cách chia này; hãy chia theo tuần hoặc tháng.");
        }
        UUID vendor = vendorOf(caller);
        Map<LocalDate, OrderSales.Bucket> found = new HashMap<>();
        sales.revenue(vendor, start.atStartOfDay(VIETNAM).toInstant(), end.plusDays(1).atStartOfDay(VIETNAM).toInstant(), g)
                .forEach(b -> found.put(b.start(), b));
        long orders = 0;
        long revenue = 0;
        List<StatsViews.Bucket> buckets = new ArrayList<>();
        for (LocalDate p : periods) {
            OrderSales.Bucket b = found.get(p);
            long o = b == null ? 0 : b.orders();
            long r = b == null ? 0 : b.revenue();
            orders += o;
            revenue += r;
            buckets.add(new StatsViews.Bucket(p, o, r, average(o, r)));
        }
        return new StatsViews.Revenue(start, end, g, new StatsViews.Totals(orders, revenue, average(orders, revenue)), buckets);
    }

    @Transactional(readOnly = true)
    public StatsViews.TopDishes topDishes(CurrentPrincipal caller, LocalDate from, LocalDate to, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "`limit` phải từ 1 đến " + MAX_LIMIT + ".");
        }
        LocalDate end = to == null ? LocalDate.now(VIETNAM) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RANGE", "`from` không được sau `to`.");
        }
        UUID vendor = vendorOf(caller);
        List<StatsViews.Dish> items = sales.topDishes(vendor, start.atStartOfDay(VIETNAM).toInstant(), end.plusDays(1).atStartOfDay(VIETNAM).toInstant(), limit)
                .stream().map(d -> new StatsViews.Dish(d.menuItemId(), d.name(), d.quantity(), d.revenue())).toList();
        return new StatsViews.TopDishes(start, end, items);
    }

    /** The first day of every day, week (Monday) or month the range touches. */
    private static List<LocalDate> periods(LocalDate from, LocalDate to, String granularity) {
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
                break;
            }
        }
        return result;
    }

    private static long average(long orders, long revenue) {
        return orders == 0 ? 0 : Math.round((double) revenue / orders);
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }
}
