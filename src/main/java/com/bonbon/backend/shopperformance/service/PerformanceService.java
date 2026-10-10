package com.bonbon.backend.shopperformance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.merchant.ShopPerformanceStanding;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.shopperformance.ShopPenaltyIssued;
import com.bonbon.backend.shopperformance.ShopRestrictionChanged;
import com.bonbon.backend.shopperformance.dto.PerformanceViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shops that fail orders are measured fairly, warned, then restricted with the legal notice (flows/shop-performance/README.md).
 * Every Monday the week that closed is evaluated: a shop with enough finished orders whose fault rate is above the threshold
 * gets one penalty point, which counts for 90 days. From 3 active points the shop is told, and 5 days later (the legal minimum)
 * it drops out of search and sorts last; if points fall below 3 first, the restriction is cancelled or lifted at once. From 6
 * points an administrator is asked to look at it; a shop is never suspended by this. Every run is safe to repeat.
 */
@Service
public class PerformanceService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int PAST_WEEKS = 8;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final OrderIncidents orders;
    private final ShopOrdering shops;
    private final ShopPerformanceStanding standing;
    private final ApplicationEventPublisher events;

    PerformanceService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, OrderIncidents orders, ShopOrdering shops, ShopPerformanceStanding standing,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.orders = orders;
        this.shops = shops;
        this.standing = standing;
        this.events = events;
    }

    record Rules(int thresholdPercent, int minOrders, int expireDays, int restrictPoints, int reviewPoints, int noticeDays) {
    }

    Rules rules() {
        return new Rules(settings.getInt("shop_performance.fault_rate_percent", 5), settings.getInt("shop_performance.min_orders", 10),
                settings.getInt("shop_performance.points_expire_days", 90), settings.getInt("shop_performance.restrict_points", 3),
                settings.getInt("shop_performance.review_points", 6), settings.getInt("legal.seller_restriction_notice_days", 5));
    }

    // --- the jobs

    /** The weekly evaluation and then every shop's standing, so expiry and scheduled restrictions are caught by the next run if one is missed. */
    @Transactional
    public void runDaily(Instant now) {
        runWeekly(now);
        evaluateAll(now);
    }

    /** Evaluates the calendar week (Monday to Monday, Vietnam time) that closed last; one point per shop above the threshold. */
    @Transactional
    public int runWeekly(Instant now) {
        Rules r = rules();
        LocalDate monday = now.atZone(VIETNAM).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekStart = monday.minusWeeks(1);
        Instant from = weekStart.atStartOfDay(VIETNAM).toInstant();
        Instant to = monday.atStartOfDay(VIETNAM).toInstant();
        Map<UUID, Long> finished = orders.finishedOrders(from, to, null);
        int issued = 0;
        for (Map.Entry<UUID, Long> entry : finished.entrySet()) {
            long total = entry.getValue();
            if (total < r.minOrders()) {
                continue;
            }
            long faults = faultOrders(entry.getKey(), from, to);
            if (faults * 100 <= (long) r.thresholdPercent() * total) {
                continue;
            }
            UUID id = jdbc.sql("""
                    insert into shop_penalties (vendor_id, points, source, week_start, finished_orders, fault_orders, issued_at, expires_at)
                    values (:v, 1, 'WEEKLY', :week, :finished, :faults, :now, :exp)
                    on conflict (vendor_id, week_start) where source = 'WEEKLY' do nothing returning id""")
                    .param("v", entry.getKey()).param("week", java.sql.Date.valueOf(weekStart)).param("finished", (int) total).param("faults", (int) faults)
                    .param("now", Timestamp.from(now)).param("exp", Timestamp.from(now.plus(Duration.ofDays(r.expireDays())))).query(UUID.class).optional().orElse(null);
            if (id != null) {
                issued++;
                events.publishEvent(new ShopPenaltyIssued(entry.getKey(), 1, activePoints(entry.getKey(), now), faults * 100.0 / total, weekStart));
            }
        }
        return issued;
    }

    @Transactional
    public void evaluateAll(Instant now) {
        for (UUID vendor : jdbc.sql("""
                select vendor_id from shop_penalties where status = 'ACTIVE' and expires_at > :now
                union select vendor_id from shop_performance_state where restriction_starts_at is not null or restricted or review_flagged""")
                .param("now", Timestamp.from(now)).query(UUID.class).list()) {
            evaluate(vendor, now);
        }
    }

    /**
     * Brings one shop's restriction in line with its active points: it is told 5 days ahead when points reach the limit, the
     * restriction begins on its date, and it is cancelled or lifted at once when points fall back under the limit.
     */
    @Transactional
    public void evaluate(UUID vendorId, Instant now) {
        Rules r = rules();
        int points = activePoints(vendorId, now);
        var state = jdbc.sql("select restriction_starts_at, restricted, review_flagged from shop_performance_state where vendor_id = :v").param("v", vendorId)
                .query((rs, n) -> new Object[] { rs.getTimestamp("restriction_starts_at") == null ? null : rs.getTimestamp("restriction_starts_at").toInstant(),
                        rs.getBoolean("restricted"), rs.getBoolean("review_flagged") }).optional().orElse(new Object[] { null, false, false });
        Instant startsAt = (Instant) state[0];
        boolean restricted = (boolean) state[1];
        Instant noticeSentAt = null;
        if (points >= r.restrictPoints()) {
            if (startsAt == null) {
                startsAt = now.plus(Duration.ofDays(r.noticeDays()));
                noticeSentAt = now;
                events.publishEvent(new ShopRestrictionChanged(vendorId, "SCHEDULED", startsAt, points));
            } else if (!restricted && !now.isBefore(startsAt)) {
                restricted = true;
                standing.setRestricted(vendorId, true);
                events.publishEvent(new ShopRestrictionChanged(vendorId, "APPLIED", startsAt, points));
            }
        } else if (startsAt != null) {
            if (restricted) {
                standing.setRestricted(vendorId, false);
            }
            events.publishEvent(new ShopRestrictionChanged(vendorId, restricted ? "LIFTED" : "CANCELLED", null, points));
            startsAt = null;
            restricted = false;
        }
        boolean review = points >= r.reviewPoints();
        jdbc.sql("""
                insert into shop_performance_state (vendor_id, notice_sent_at, restriction_starts_at, restricted, review_flagged, updated_at)
                values (:v, :notice, :starts, :restricted, :review, :now)
                on conflict (vendor_id) do update set notice_sent_at = coalesce(excluded.notice_sent_at, shop_performance_state.notice_sent_at),
                       restriction_starts_at = excluded.restriction_starts_at, restricted = excluded.restricted, review_flagged = excluded.review_flagged,
                       updated_at = excluded.updated_at""")
                .param("v", vendorId).param("notice", noticeSentAt == null ? null : Timestamp.from(noticeSentAt)).param("starts", startsAt == null ? null : Timestamp.from(startsAt))
                .param("restricted", restricted).param("review", review).param("now", Timestamp.from(now)).update();
    }

    int activePoints(UUID vendorId, Instant now) {
        return Math.max(0, jdbc.sql("select coalesce(sum(points), 0) from shop_penalties where vendor_id = :v and status = 'ACTIVE' and expires_at > :now")
                .param("v", vendorId).param("now", Timestamp.from(now)).query(Integer.class).single());
    }

    private long faultOrders(UUID vendorId, Instant from, Instant to) {
        return jdbc.sql("select count(distinct order_id) from shop_fault_events where vendor_id = :v and occurred_at >= :from and occurred_at < :to")
                .param("v", vendorId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).query(Long.class).single();
    }

    // --- what the shop sees

    @Transactional(readOnly = true)
    public PerformanceViews.Summary summary(CurrentPrincipal caller) {
        UUID vendor = vendorOf(caller);
        Instant now = clock.instant();
        Rules r = rules();
        LocalDate monday = now.atZone(VIETNAM).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<PerformanceViews.Week> weeks = new ArrayList<>();
        for (int i = 0; i <= PAST_WEEKS; i++) {
            LocalDate start = monday.minusWeeks(i);
            Instant from = start.atStartOfDay(VIETNAM).toInstant();
            Instant to = i == 0 ? now.plusSeconds(1) : start.plusWeeks(1).atStartOfDay(VIETNAM).toInstant();
            long finished = orders.finishedOrders(from, to, vendor).getOrDefault(vendor, 0L);
            long faults = faultOrders(vendor, from, to);
            boolean penalised = i > 0 && jdbc.sql("select exists (select 1 from shop_penalties where vendor_id = :v and week_start = :w and source = 'WEEKLY')").param("v", vendor)
                    .param("w", java.sql.Date.valueOf(start)).query(Boolean.class).single();
            weeks.add(new PerformanceViews.Week(start, start.plusDays(6), finished, faults,
                    finished == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(faults * 100.0 / finished).setScale(1, RoundingMode.HALF_UP), finished >= r.minOrders(), penalised, i == 0));
        }
        List<PerformanceViews.Penalty> penalties = jdbc.sql("""
                select id, points, source, week_start, reason, status, issued_at, expires_at, appeal_status, coalesce(appeal_decision_reason, decision_reason) as decision
                from shop_penalties where vendor_id = :v order by issued_at desc, id limit 50""")
                .param("v", vendor).query((rs, n) -> {
                    Instant issued = rs.getTimestamp("issued_at").toInstant();
                    Instant deadline = issued.plus(Duration.ofDays(settings.getLong("appeal_window_days", 7)));
                    boolean canAppeal = "ACTIVE".equals(rs.getString("status")) && rs.getString("appeal_status") == null && now.isBefore(deadline)
                            && rs.getTimestamp("expires_at").toInstant().isAfter(now);
                    return new PerformanceViews.Penalty(rs.getObject("id", UUID.class), rs.getInt("points"), rs.getString("source"),
                            rs.getDate("week_start") == null ? null : rs.getDate("week_start").toLocalDate(), rs.getString("reason"), rs.getString("status"), issued,
                            rs.getTimestamp("expires_at").toInstant(), canAppeal, deadline, rs.getString("appeal_status"), rs.getString("decision"));
                }).list();
        return new PerformanceViews.Summary(standingOf(vendor, now, r), weeks, penalties, r.thresholdPercent(), r.minOrders());
    }

    @Transactional(readOnly = true)
    public List<PerformanceViews.Fault> faults(CurrentPrincipal caller, LocalDate week) {
        UUID vendor = vendorOf(caller);
        if (week.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_WEEK", "`week` phải là ngày thứ Hai đầu tuần.");
        }
        Instant from = week.atStartOfDay(VIETNAM).toInstant();
        Instant to = week.plusWeeks(1).atStartOfDay(VIETNAM).toInstant();
        return jdbc.sql("select order_id, order_number, type, occurred_at from shop_fault_events where vendor_id = :v and occurred_at >= :from and occurred_at < :to order by occurred_at")
                .param("v", vendor).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new PerformanceViews.Fault(rs.getObject("order_id", UUID.class), rs.getLong("order_number"), rs.getString("type"),
                        rs.getTimestamp("occurred_at").toInstant())).list();
    }

    PerformanceViews.Standing standingOf(UUID vendor, Instant now, Rules r) {
        int points = activePoints(vendor, now);
        var state = jdbc.sql("select restriction_starts_at, restricted from shop_performance_state where vendor_id = :v").param("v", vendor)
                .query((rs, n) -> new Object[] { rs.getTimestamp("restriction_starts_at") == null ? null : rs.getTimestamp("restriction_starts_at").toInstant(), rs.getBoolean("restricted") })
                .optional().orElse(new Object[] { null, false });
        Instant startsAt = (Instant) state[0];
        boolean restricted = (boolean) state[1];
        String consequence = restricted ? "RESTRICTED" : startsAt != null ? "RESTRICTION_SCHEDULED" : points > 0 ? "WARNING" : "NONE";
        return new PerformanceViews.Standing(points, consequence, startsAt, startsAt != null || restricted ? "PERFORMANCE" : null);
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt."));
    }
}
