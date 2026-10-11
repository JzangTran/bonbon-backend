package com.bonbon.backend.platformstatistics.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.platformstatistics.dto.MerchantViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding shops and seeing where the platform has coverage (flows/merchant-approval/view-active-merchants.md, view-area-coverage.md).
 * Read only: nothing here changes a shop. The coverage view is the answer to "which areas do we cover" now that areas are not
 * drawn by an administrator but come out of every shop's own delivery radius.
 */
@Service
public class MerchantDirectoryService {

    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "SUSPENDED", "CLOSED");
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;

    MerchantDirectoryService(JdbcClient jdbc, Clock clock, SystemSettingsService settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    /** {@code status} defaults to APPROVED; {@code ALL} lists every shop that has been submitted. */
    @Transactional(readOnly = true)
    public MerchantViews.Page list(String status, String q, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        String s = status == null ? "APPROVED" : status;
        if (!s.equals("ALL") && !STATUSES.contains(s)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STATUS_FILTER_INVALID", "Bộ lọc trạng thái không hợp lệ.");
        }
        boolean search = q != null && !q.isBlank();
        String where = " where v.status <> 'DRAFT' and (:status = 'ALL' or v.status = :status)"
                + (search ? " and (unaccent(lower(v.name)) like unaccent(:pattern) or unaccent(lower(coalesce(v.ward, ''))) like unaccent(:pattern) or unaccent(lower(coalesce(v.province, ''))) like unaccent(:pattern))" : "");
        String pattern = search ? "%" + q.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" : null;
        JdbcClient.StatementSpec count = jdbc.sql("select count(*) from vendors v" + where).param("status", s);
        JdbcClient.StatementSpec rows = jdbc.sql("""
                select v.id, v.name, v.ward, v.province, v.status, v.delivery_radius_km, v.rating_sum, v.rating_count, v.performance_restricted, v.commission_stage, v.created_at,
                       (select count(*) from orders o where o.vendor_id = v.id and o.status = 'DELIVERED') as delivered,
                       (select coalesce(sum(p.points), 0) from shop_penalties p where p.vendor_id = v.id and p.status = 'ACTIVE' and p.expires_at > :now) as points,
                       (select m.status from merchant_suspensions m where m.vendor_id = v.id and m.status in ('SCHEDULED', 'APPLIED') limit 1) as suspension
                from vendors v""" + where + " order by v.created_at desc, v.id limit :limit offset :offset")
                .param("status", s).param("now", Timestamp.from(clock.instant())).param("limit", size).param("offset", (long) page * size);
        if (search) {
            count = count.param("pattern", pattern);
            rows = rows.param("pattern", pattern);
        }
        long total = count.query(Long.class).single();
        List<MerchantViews.Row> items = rows.query((rs, n) -> {
            int ratings = rs.getInt("rating_count");
            return new MerchantViews.Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("ward"), rs.getString("province"), rs.getString("status"),
                    rs.getBigDecimal("delivery_radius_km"), ratings == 0 ? null : Math.round(rs.getInt("rating_sum") * 10.0 / ratings) / 10.0, ratings,
                    rs.getLong("delivered"), rs.getInt("points"), rs.getBoolean("performance_restricted"), rs.getString("commission_stage"), rs.getString("suspension"),
                    rs.getTimestamp("created_at").toInstant());
        }).list();
        return new MerchantViews.Page(items, page, size, total);
    }

    /** Approved shops with their position and radius; a shop whose radius is far above the norm of its province is flagged. */
    @Transactional(readOnly = true)
    public MerchantViews.Coverage coverage(String province, String ward) {
        double factor = Double.parseDouble(settings.getString("coverage.outlier_factor", "2"));
        boolean byProvince = province != null && !province.isBlank();
        boolean byWard = ward != null && !ward.isBlank();
        String where = " where v.status = 'APPROVED' and v.lat is not null and v.lng is not null and v.delivery_radius_km is not null"
                + (byProvince ? " and unaccent(lower(v.province)) = unaccent(lower(:province))" : "")
                + (byWard ? " and unaccent(lower(v.ward)) = unaccent(lower(:ward))" : "");
        JdbcClient.StatementSpec spec = jdbc.sql("""
                select v.id, v.name, v.formatted_address, v.ward, v.province, v.lat, v.lng, v.delivery_radius_km,
                       (select count(*) from orders o where o.vendor_id = v.id and o.status = 'DELIVERED') as delivered,
                       (select cast(percentile_cont(0.5) within group (order by a.delivery_radius_km) as numeric(6,2)) from vendors a
                        where a.status = 'APPROVED' and a.delivery_radius_km is not null and a.province is not distinct from v.province) as median
                from vendors v""" + where + " order by v.province, v.ward, v.name, v.id");
        if (byProvince) {
            spec = spec.param("province", province.strip());
        }
        if (byWard) {
            spec = spec.param("ward", ward.strip());
        }
        List<MerchantViews.CoverageShop> items = spec.query((rs, n) -> {
            BigDecimal radius = rs.getBigDecimal("delivery_radius_km");
            BigDecimal median = rs.getBigDecimal("median");
            boolean outlier = median != null && median.signum() > 0 && radius.doubleValue() > factor * median.doubleValue();
            return new MerchantViews.CoverageShop(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("formatted_address"), rs.getString("ward"),
                    rs.getString("province"), rs.getDouble("lat"), rs.getDouble("lng"), radius, rs.getLong("delivered"), median, outlier);
        }).list();
        return new MerchantViews.Coverage(factor, items);
    }
}
