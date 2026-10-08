package com.bonbon.backend.shopperformance.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopNames;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.shopperformance.ShopPenaltyDecided;
import com.bonbon.backend.shopperformance.dto.PenaltyViews;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An administrator correcting penalty points and a shop challenging one (flows/shop-performance/manage-penalties.md). Waiving a
 * point recomputes the shop's standing at once, so it can lift a restriction in the same moment. A shop may appeal each point once,
 * inside {@code appeal_window_days} of its issue; the appeal does not lift anything while it waits, and accepting it waives the point.
 */
@Service
public class PenaltyService {

    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ShopNames shopNames;
    private final ShopOrdering shops;
    private final PerformanceService performance;
    private final ApplicationEventPublisher events;

    PenaltyService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ShopNames shopNames, ShopOrdering shops, PerformanceService performance,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.shopNames = shopNames;
        this.shops = shops;
        this.performance = performance;
        this.events = events;
    }

    // --- reading

    /** Shops with at least {@code minPoints} active points, most points first. */
    @Transactional(readOnly = true)
    public PenaltyViews.ShopPage shops(int minPoints, int page, int size) {
        checkPage(page, size);
        if (minPoints < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MIN_POINTS", "`minPoints` không được âm.");
        }
        Timestamp now = Timestamp.from(clock.instant());
        // A shop that has appeals waiting is listed even with fewer points, so no appeal is lost behind the filter.
        String base = """
                from (select vendor_id, coalesce(sum(points) filter (where status = 'ACTIVE' and expires_at > :now), 0) as points,
                             count(*) filter (where appeal_status = 'PENDING') as pending
                      from shop_penalties group by vendor_id) p
                where p.points >= :min or p.pending > 0""";
        long total = jdbc.sql("select count(*) " + base).param("now", now).param("min", Math.max(minPoints, 1)).query(Long.class).single();
        record Row(UUID vendor, int points, long pending) {
        }
        List<Row> rows = jdbc.sql("select p.vendor_id, p.points, p.pending " + base + " order by p.points desc, p.pending desc, p.vendor_id limit :limit offset :offset")
                .param("now", now).param("min", Math.max(minPoints, 1)).param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Row(rs.getObject("vendor_id", UUID.class), rs.getInt("points"), rs.getLong("pending"))).list();
        Map<UUID, String> names = shopNames.names(rows.stream().map(Row::vendor).toList());
        List<PenaltyViews.ShopRow> items = rows.stream().map(r -> {
            var standing = performance.standingOf(r.vendor(), clock.instant(), performance.rules());
            return new PenaltyViews.ShopRow(r.vendor(), names.getOrDefault(r.vendor(), "(đã xoá)"), r.points(), standing.consequence(), standing.restrictionStartsAt(),
                    reviewFlagged(r.vendor()), r.pending());
        }).toList();
        return new PenaltyViews.ShopPage(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public PenaltyViews.History history(UUID vendorId) {
        String name = shopNames.names(List.of(vendorId)).get(vendorId);
        if (name == null) {
            throw BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán.");
        }
        Instant now = clock.instant();
        var standing = performance.standingOf(vendorId, now, performance.rules());
        List<PenaltyViews.Penalty> penalties = jdbc.sql("""
                select id, points, source, week_start, finished_orders, fault_orders, reason, status, issued_at, expires_at, appeal_status, appeal_reason, appealed_at,
                       appeal_decision_reason, decision_reason
                from shop_penalties where vendor_id = :v order by issued_at desc, id""").param("v", vendorId)
                .query((rs, n) -> new PenaltyViews.Penalty(rs.getObject("id", UUID.class), rs.getInt("points"), rs.getString("source"),
                        rs.getDate("week_start") == null ? null : rs.getDate("week_start").toLocalDate(), (Integer) rs.getObject("finished_orders"), (Integer) rs.getObject("fault_orders"),
                        rs.getString("reason"), rs.getString("status"), rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        !rs.getTimestamp("expires_at").toInstant().isAfter(now), rs.getString("appeal_status"), rs.getString("appeal_reason"),
                        rs.getTimestamp("appealed_at") == null ? null : rs.getTimestamp("appealed_at").toInstant(), rs.getString("appeal_decision_reason"),
                        rs.getString("decision_reason"))).list();
        return new PenaltyViews.History(vendorId, name, standing.activePoints(), standing.consequence(), standing.restrictionStartsAt(), reviewFlagged(vendorId), penalties);
    }

    /** The appeals waiting for a decision, oldest first. */
    @Transactional(readOnly = true)
    public PenaltyViews.AppealPage appeals(int page, int size) {
        checkPage(page, size);
        long total = jdbc.sql("select count(*) from shop_penalties where appeal_status = 'PENDING'").query(Long.class).single();
        record Row(UUID id, UUID vendor, int points, java.time.LocalDate week, Integer finished, Integer faults, Instant issued, String reason, Instant appealed) {
        }
        List<Row> rows = jdbc.sql("""
                select id, vendor_id, points, week_start, finished_orders, fault_orders, issued_at, appeal_reason, appealed_at from shop_penalties
                where appeal_status = 'PENDING' order by appealed_at, id limit :limit offset :offset""").param("limit", size).param("offset", (long) page * size)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class), rs.getInt("points"),
                        rs.getDate("week_start") == null ? null : rs.getDate("week_start").toLocalDate(), (Integer) rs.getObject("finished_orders"), (Integer) rs.getObject("fault_orders"),
                        rs.getTimestamp("issued_at").toInstant(), rs.getString("appeal_reason"), rs.getTimestamp("appealed_at").toInstant())).list();
        Map<UUID, String> names = shopNames.names(rows.stream().map(Row::vendor).distinct().toList());
        return new PenaltyViews.AppealPage(rows.stream().map(r -> new PenaltyViews.Appeal(r.id(), r.vendor(), names.getOrDefault(r.vendor(), "(đã xoá)"), r.points(), r.week(),
                r.finished(), r.faults(), r.issued(), r.reason(), r.appealed())).toList(), page, size, total);
    }

    // --- the administrator changes points

    @Transactional
    public PenaltyViews.History waive(CurrentPrincipal admin, UUID penaltyId, String reason) {
        UUID vendor = activePenaltyVendor(penaltyId);
        int changed = jdbc.sql("""
                update shop_penalties set status = 'WAIVED', decided_by_type = :by, decided_by_id = :admin, decided_at = :at, decision_reason = :reason
                where id = :id and status = 'ACTIVE'""").param("by", ActorType.ADMIN.name()).param("admin", admin.id()).param("at", Timestamp.from(clock.instant()))
                .param("reason", reason.strip()).param("id", penaltyId).update();
        if (changed == 0) {
            throw BusinessException.conflict("PENALTY_NOT_ACTIVE", "Điểm này đã được miễn.");
        }
        return settled(vendor, penaltyId, "WAIVED", reason.strip());
    }

    /** A point the weekly job did not give, with a reason the shop reads; it expires after 90 days like any other. */
    @Transactional
    public PenaltyViews.History add(CurrentPrincipal admin, UUID vendorId, int points, String reason) {
        if (shopNames.names(List.of(vendorId)).get(vendorId) == null) {
            throw BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán.");
        }
        Instant now = clock.instant();
        int days = performance.rules().expireDays();
        UUID id = jdbc.sql("""
                insert into shop_penalties (vendor_id, points, source, reason, issued_at, expires_at, decided_by_type, decided_by_id, decided_at)
                values (:v, :points, 'MANUAL', :reason, :now, :exp, :by, :admin, :now) returning id""")
                .param("v", vendorId).param("points", points).param("reason", reason.strip()).param("now", Timestamp.from(now)).param("exp", Timestamp.from(now.plus(Duration.ofDays(days))))
                .param("by", ActorType.ADMIN.name()).param("admin", admin.id()).query(UUID.class).single();
        return settled(vendorId, id, "ADDED", reason.strip());
    }

    /** Accepting waives the point; rejecting keeps it. Either way the shop reads the reason. */
    @Transactional
    public PenaltyViews.History decideAppeal(CurrentPrincipal admin, UUID penaltyId, String decision, String reason) {
        boolean accept = "ACCEPT".equals(decision);
        Instant now = clock.instant();
        UUID vendor = jdbc.sql("""
                update shop_penalties set appeal_status = :status, appeal_decided_by_id = :admin, appeal_decided_at = :at, appeal_decision_reason = :reason,
                       status = case when :accept then 'WAIVED' else status end,
                       decided_by_type = case when :accept then 'ADMIN' else decided_by_type end, decided_by_id = case when :accept then cast(:admin as uuid) else decided_by_id end,
                       decided_at = case when :accept then cast(:at as timestamptz) else decided_at end, decision_reason = case when :accept then :reason else decision_reason end
                where id = :id and appeal_status = 'PENDING' returning vendor_id""")
                .param("status", accept ? "ACCEPTED" : "REJECTED").param("admin", admin.id()).param("at", Timestamp.from(now)).param("reason", reason.strip()).param("accept", accept)
                .param("id", penaltyId).query(UUID.class).optional().orElse(null);
        if (vendor == null) {
            boolean exists = jdbc.sql("select exists (select 1 from shop_penalties where id = :id)").param("id", penaltyId).query(Boolean.class).single();
            if (!exists) {
                throw BusinessException.notFound("PENALTY_NOT_FOUND", "Không tìm thấy điểm phạt.");
            }
            throw BusinessException.conflict("APPEAL_NOT_PENDING", "Điểm này không có kháng nghị đang chờ.");
        }
        return settled(vendor, penaltyId, accept ? "APPEAL_ACCEPTED" : "APPEAL_REJECTED", reason.strip());
    }

    // --- the shop appeals

    @Transactional
    public void appeal(CurrentPrincipal caller, UUID penaltyId, String reason) {
        UUID vendor = shops.approvedVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt."));
        var row = jdbc.sql("select status, issued_at, appeal_status from shop_penalties where id = :id and vendor_id = :v").param("id", penaltyId).param("v", vendor)
                .query((rs, n) -> new Object[] { rs.getString("status"), rs.getTimestamp("issued_at").toInstant(), rs.getString("appeal_status") }).optional()
                .orElseThrow(() -> BusinessException.notFound("PENALTY_NOT_FOUND", "Không tìm thấy điểm phạt."));
        if ("WAIVED".equals(row[0])) {
            throw BusinessException.conflict("PENALTY_NOT_ACTIVE", "Điểm này đã được miễn.");
        }
        if (row[2] != null) {
            throw BusinessException.conflict("APPEAL_ALREADY_FILED", "Điểm này đã được kháng nghị.");
        }
        Instant deadline = ((Instant) row[1]).plus(Duration.ofDays(settings.getLong("appeal_window_days", 7)));
        if (!clock.instant().isBefore(deadline)) {
            throw BusinessException.conflict("APPEAL_WINDOW_CLOSED", "Đã quá thời hạn kháng nghị điểm này.").withProperty("deadline", deadline);
        }
        jdbc.sql("update shop_penalties set appeal_status = 'PENDING', appeal_reason = :reason, appealed_at = :at where id = :id and appeal_status is null")
                .param("reason", reason.strip()).param("at", Timestamp.from(clock.instant())).param("id", penaltyId).update();
    }

    // --- internals

    private PenaltyViews.History settled(UUID vendorId, UUID penaltyId, String kind, String reason) {
        performance.evaluate(vendorId, clock.instant());
        events.publishEvent(new ShopPenaltyDecided(vendorId, penaltyId, kind, performance.activePoints(vendorId, clock.instant()), reason));
        return history(vendorId);
    }

    private UUID activePenaltyVendor(UUID penaltyId) {
        return jdbc.sql("select vendor_id from shop_penalties where id = :id").param("id", penaltyId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("PENALTY_NOT_FOUND", "Không tìm thấy điểm phạt."));
    }

    private boolean reviewFlagged(UUID vendorId) {
        return jdbc.sql("select coalesce((select review_flagged from shop_performance_state where vendor_id = :v), false)").param("v", vendorId).query(Boolean.class).single();
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
    }
}
