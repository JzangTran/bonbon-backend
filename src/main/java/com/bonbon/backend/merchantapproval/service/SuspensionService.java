package com.bonbon.backend.merchantapproval.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchantapproval.ShopSuspensionChanged;
import com.bonbon.backend.merchantapproval.dto.SuspensionDtos;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspending a shop (flows/merchant-approval/suspend-seller.md). Restricting a seller's account takes a notice of at least 5 days
 * (the legal minimum), so a suspension is scheduled: the shop is told the reason and the date, keeps working until then, and a job
 * suspends it when the time comes. Only a competent authority's request is immediate. An administrator can call a scheduled one off,
 * and can reinstate a suspended shop; both need a reason. A suspended seller can still log in, finish the orders in progress and read
 * what it earns and why it was suspended, but is not found by customers and cannot take new orders or change its menu.
 */
@Service
public class SuspensionService {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private final ShopApplications shops;
    private final ShopOrdering ownership;
    private final ApplicationEventPublisher events;

    SuspensionService(JdbcClient jdbc, Clock clock, SystemSettingsService settings, ShopApplications shops, ShopOrdering ownership, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
        this.shops = shops;
        this.ownership = ownership;
        this.events = events;
    }

    // --- the administrator

    @Transactional
    public SuspensionDtos.Suspension suspend(CurrentPrincipal admin, UUID vendorId, SuspensionDtos.Suspend request) {
        var shop = shops.detail(vendorId).orElseThrow(() -> BusinessException.notFound("SHOP_NOT_FOUND", "Không tìm thấy cửa hàng."));
        if (shop.status() != VendorStatus.APPROVED) {
            throw BusinessException.conflict("SHOP_NOT_APPROVED", "Chỉ đình chỉ được cửa hàng đang hoạt động.").withProperty("status", shop.status().name());
        }
        Instant now = clock.instant();
        boolean immediate = Boolean.TRUE.equals(request.immediate());
        String authority = request.authorityReference() == null || request.authorityReference().isBlank() ? null : request.authorityReference().strip();
        Instant effective;
        if (immediate) {
            if (authority == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "AUTHORITY_REFERENCE_REQUIRED", "Đình chỉ ngay cần số hiệu và ngày của yêu cầu từ cơ quan có thẩm quyền.");
            }
            if (request.effectiveAt() != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "EFFECTIVE_AT_NOT_ALLOWED", "Đình chỉ ngay không có ngày hiệu lực.");
            }
            effective = now;
        } else {
            Instant earliest = now.plus(Duration.ofDays(settings.getInt("legal.seller_restriction_notice_days", 5)));
            effective = request.effectiveAt() == null ? earliest : request.effectiveAt();
            if (effective.isBefore(earliest)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTICE_TOO_SHORT", "Phải báo trước cho quán ít nhất "
                        + settings.getInt("legal.seller_restriction_notice_days", 5) + " ngày.").withProperty("earliest", earliest);
            }
        }
        UUID id;
        try {
            id = jdbc.sql("""
                    insert into merchant_suspensions (vendor_id, kind, status, reason, authority_reference, notice_sent_at, effective_at, created_by_id, created_at)
                    values (:v, :kind, 'SCHEDULED', :reason, :authority, :now, :effective, :admin, :now) returning id""")
                    .param("v", vendorId).param("kind", immediate ? "IMMEDIATE" : "SCHEDULED").param("reason", request.reason().strip()).param("authority", authority)
                    .param("now", Timestamp.from(now)).param("effective", Timestamp.from(effective)).param("admin", admin.id()).query(UUID.class).single();
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("SUSPENSION_ALREADY_OPEN", "Cửa hàng này đã có lịch đình chỉ hoặc đang bị đình chỉ.");
        }
        if (immediate) {
            apply(id, vendorId, now);
        } else {
            events.publishEvent(new ShopSuspensionChanged(vendorId, "SCHEDULED", request.reason().strip(), effective));
        }
        return one(id);
    }

    /** Calls off a suspension that has not started: the shop kept working all along. */
    @Transactional
    public SuspensionDtos.Suspension cancel(CurrentPrincipal admin, UUID vendorId, String reason) {
        UUID id = jdbc.sql("""
                update merchant_suspensions set status = 'CANCELLED', ended_by_id = :admin, ended_at = :now, end_reason = :reason
                where vendor_id = :v and status = 'SCHEDULED' returning id""").param("admin", admin.id()).param("now", Timestamp.from(clock.instant()))
                .param("reason", reason.strip()).param("v", vendorId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.conflict("NO_SCHEDULED_SUSPENSION", "Cửa hàng này không có lịch đình chỉ nào đang chờ."));
        events.publishEvent(new ShopSuspensionChanged(vendorId, "CANCELLED", reason.strip(), null));
        return one(id);
    }

    /** SUSPENDED back to APPROVED, with the reason on record. */
    @Transactional
    public SuspensionDtos.Suspension reinstate(CurrentPrincipal admin, UUID vendorId, String reason) {
        UUID id = jdbc.sql("""
                update merchant_suspensions set status = 'LIFTED', ended_by_id = :admin, ended_at = :now, end_reason = :reason
                where vendor_id = :v and status = 'APPLIED' returning id""").param("admin", admin.id()).param("now", Timestamp.from(clock.instant()))
                .param("reason", reason.strip()).param("v", vendorId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.conflict("SHOP_NOT_SUSPENDED", "Cửa hàng này không bị đình chỉ."));
        shops.reinstate(vendorId);
        events.publishEvent(new ShopSuspensionChanged(vendorId, "LIFTED", reason.strip(), null));
        return one(id);
    }

    @Transactional(readOnly = true)
    public SuspensionDtos.History history(UUID vendorId) {
        shops.detail(vendorId).orElseThrow(() -> BusinessException.notFound("SHOP_NOT_FOUND", "Không tìm thấy cửa hàng."));
        return new SuspensionDtos.History(jdbc.sql(SELECT + " where vendor_id = :v order by created_at desc, id").param("v", vendorId).query(ROW).list());
    }

    // --- the job

    /** Suspends every shop whose notice has run out. Safe to run twice; a shop that closed or was suspended meanwhile is left alone. */
    @Transactional
    public int applyDue(Instant now) {
        int applied = 0;
        for (var due : jdbc.sql("select id, vendor_id from merchant_suspensions where status = 'SCHEDULED' and effective_at <= :now order by effective_at")
                .param("now", Timestamp.from(now)).query((rs, n) -> new UUID[] { rs.getObject("id", UUID.class), rs.getObject("vendor_id", UUID.class) }).list()) {
            // Look first instead of catching a failure: an exception inside this transaction would poison it for every shop after.
            boolean approved = shops.detail(due[1]).map(d -> d.status() == VendorStatus.APPROVED).orElse(false);
            if (approved) {
                apply(due[0], due[1], now);
                applied++;
            } else {
                jdbc.sql("update merchant_suspensions set status = 'CANCELLED', ended_at = :now, end_reason = 'Cửa hàng không còn hoạt động' where id = :id and status = 'SCHEDULED'")
                        .param("now", Timestamp.from(now)).param("id", due[0]).update();
            }
        }
        return applied;
    }

    // --- the seller

    @Transactional(readOnly = true)
    public SuspensionDtos.Mine mine(CurrentPrincipal caller) {
        UUID vendor = ownership.operatingVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt."));
        return jdbc.sql("select status, reason, effective_at, notice_sent_at from merchant_suspensions where vendor_id = :v and status in ('SCHEDULED', 'APPLIED')").param("v", vendor)
                .query((rs, n) -> new SuspensionDtos.Mine("APPLIED".equals(rs.getString("status")) ? "SUSPENDED" : "SCHEDULED", rs.getString("reason"),
                        rs.getTimestamp("effective_at").toInstant(), rs.getTimestamp("notice_sent_at").toInstant())).optional()
                .orElse(new SuspensionDtos.Mine("NONE", null, null, null));
    }

    // --- internals

    private void apply(UUID suspensionId, UUID vendorId, Instant now) {
        shops.suspend(vendorId);
        jdbc.sql("update merchant_suspensions set status = 'APPLIED', applied_at = :now where id = :id and status = 'SCHEDULED'").param("now", Timestamp.from(now)).param("id", suspensionId).update();
        var s = one(suspensionId);
        events.publishEvent(new ShopSuspensionChanged(vendorId, "APPLIED", s.reason(), s.effectiveAt()));
    }

    private SuspensionDtos.Suspension one(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(ROW).single();
    }

    private static final String SELECT = "select id, kind, status, reason, authority_reference, notice_sent_at, effective_at, created_at, applied_at, ended_at, end_reason from merchant_suspensions";

    private static final org.springframework.jdbc.core.RowMapper<SuspensionDtos.Suspension> ROW = (rs, n) -> new SuspensionDtos.Suspension(rs.getObject("id", UUID.class),
            rs.getString("kind"), rs.getString("status"), rs.getString("reason"), rs.getString("authority_reference"), rs.getTimestamp("notice_sent_at").toInstant(),
            rs.getTimestamp("effective_at").toInstant(), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("applied_at") == null ? null : rs.getTimestamp("applied_at").toInstant(),
            rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(), rs.getString("end_reason"));
}
