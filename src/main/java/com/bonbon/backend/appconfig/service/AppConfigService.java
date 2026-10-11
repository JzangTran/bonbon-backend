package com.bonbon.backend.appconfig.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bonbon.backend.appconfig.MaintenanceState;
import com.bonbon.backend.appconfig.dto.AppConfigRequests;
import com.bonbon.backend.appconfig.dto.AppConfigViews;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The oldest app build still supported and maintenance mode (app-operations.md). The filter asks this for every request, so
 * the current values are kept for a few seconds and dropped whenever an administrator changes them.
 */
@Service
public class AppConfigService implements MaintenanceState {

    static final String LEVEL = "maintenance.level";
    static final String MESSAGE = "maintenance.message";
    static final String STARTS = "maintenance.starts_at";
    static final String ENDS = "maintenance.ends_at";
    private static final Set<String> PLATFORMS = Set.of("ANDROID", "IOS");
    private static final Duration KEEP = Duration.ofSeconds(5);

    /** One consistent look at both settings. */
    record Snapshot(Map<String, AppConfigViews.Policy> policies, AppConfigViews.Maintenance maintenance, Instant takenAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final SystemSettingsService settings;
    private volatile Snapshot snapshot;

    AppConfigService(JdbcClient jdbc, Clock clock, SystemSettingsService settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.settings = settings;
    }

    // --- what the apps and the filter read

    /** What an app of this platform and version is told. {@code platform} and {@code version} are null for a client that sends neither (web). */
    @Transactional(readOnly = true)
    public AppConfigViews.Config config(String platform, String version) {
        Snapshot s = current();
        AppConfigViews.Policy policy = platform == null ? null : s.policies().get(platform);
        boolean required = policy != null && AppVersions.below(version, policy.minSupportedVersion());
        boolean available = policy != null && !required && AppVersions.below(version, policy.latestVersion());
        return new AppConfigViews.Config(clock.instant(), policy == null ? null : platform, policy == null ? null : policy.minSupportedVersion(),
                policy == null ? null : policy.latestVersion(), policy == null ? null : policy.storeUrl(), policy == null ? null : policy.message(), required, available,
                s.maintenance());
    }

    /** True when {@code version} of {@code platform} is older than the minimum; false for an unknown platform or an unreadable version. */
    public boolean tooOld(String platform, String version) {
        AppConfigViews.Policy policy = platform == null ? null : current().policies().get(platform.toUpperCase());
        return policy != null && AppVersions.below(version, policy.minSupportedVersion());
    }

    public String storeUrl(String platform) {
        AppConfigViews.Policy policy = platform == null ? null : current().policies().get(platform.toUpperCase());
        return policy == null ? null : policy.storeUrl();
    }

    public AppConfigViews.Maintenance maintenance() {
        return current().maintenance();
    }

    @Override
    public boolean active() {
        return current().maintenance().active();
    }

    // --- administrators

    @Transactional(readOnly = true)
    public AppConfigViews.Admin admin() {
        Snapshot s = load();
        return new AppConfigViews.Admin(List.copyOf(s.policies().values()), s.maintenance());
    }

    @Transactional
    public AppConfigViews.Admin setVersionPolicy(CurrentPrincipal admin, Instant authTime, String platform, AppConfigRequests.VersionPolicy request) {
        freshly(authTime);
        String p = platform == null ? "" : platform.toUpperCase();
        if (!PLATFORMS.contains(p)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PLATFORM_INVALID", "Nền tảng phải là ANDROID hoặc IOS.");
        }
        if (AppVersions.below(request.latestVersion(), request.minSupportedVersion())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LATEST_BELOW_MINIMUM", "Bản mới nhất không được thấp hơn bản tối thiểu.");
        }
        jdbc.sql("""
                update app_version_policies set min_supported_version = :min, latest_version = :latest, store_url = :url, message = :msg, updated_by_id = :admin, updated_at = :now
                where platform = :p""")
                .param("min", request.minSupportedVersion()).param("latest", request.latestVersion()).param("url", blankToNull(request.storeUrl()))
                .param("msg", blankToNull(request.message())).param("admin", admin.id()).param("now", Timestamp.from(clock.instant())).param("p", p).update();
        log(admin, "VERSION_POLICY", p, "tối thiểu " + request.minSupportedVersion() + ", mới nhất " + request.latestVersion(), request.reason());
        snapshot = null;
        return admin();
    }

    @Transactional
    public AppConfigViews.Admin setMaintenance(CurrentPrincipal admin, Instant authTime, AppConfigRequests.Maintenance request) {
        freshly(authTime);
        if (request.startsAt() != null && request.endsAt() != null && !request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MAINTENANCE_WINDOW_INVALID", "Giờ kết thúc phải sau giờ bắt đầu.");
        }
        settings.set(LEVEL, request.level(), admin.actorType(), admin.id());
        settings.set(MESSAGE, request.message() == null ? "" : request.message().strip(), admin.actorType(), admin.id());
        settings.set(STARTS, request.startsAt() == null ? "" : request.startsAt().toString(), admin.actorType(), admin.id());
        settings.set(ENDS, request.endsAt() == null ? "" : request.endsAt().toString(), admin.actorType(), admin.id());
        log(admin, "MAINTENANCE", "maintenance", request.level() + (request.endsAt() == null ? "" : " đến " + request.endsAt()), request.reason());
        snapshot = null;
        return admin();
    }

    // --- internals

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null || s.takenAt().plus(KEEP).isBefore(clock.instant())) {
            s = load();
            snapshot = s;
        }
        return s;
    }

    private Snapshot load() {
        Map<String, AppConfigViews.Policy> policies = new java.util.LinkedHashMap<>();
        jdbc.sql("select platform, min_supported_version, latest_version, store_url, message, updated_at from app_version_policies order by platform")
                .query((rs, n) -> policies.put(rs.getString("platform"), new AppConfigViews.Policy(rs.getString("platform"), rs.getString("min_supported_version"),
                        rs.getString("latest_version"), rs.getString("store_url"), rs.getString("message"), rs.getTimestamp("updated_at").toInstant()))).list();
        String level = settings.getString(LEVEL, "NONE");
        Instant starts = instant(settings.getString(STARTS, ""));
        Instant ends = instant(settings.getString(ENDS, ""));
        Instant now = clock.instant();
        boolean active = !level.equals("NONE") && (starts == null || !starts.isAfter(now)) && (ends == null || ends.isAfter(now));
        String message = settings.getString(MESSAGE, "");
        return new Snapshot(policies, new AppConfigViews.Maintenance(level, active, message.isBlank() ? null : message, starts, ends), now);
    }

    private void log(CurrentPrincipal admin, String action, String subject, String details, String reason) {
        jdbc.sql("insert into app_config_log (admin_id, action, subject, details, reason, at) values (:a, :action, :s, :d, :r, :at)")
                .param("a", admin.id()).param("action", action).param("s", subject).param("d", details).param("r", reason.strip()).param("at", Timestamp.from(clock.instant())).update();
    }

    /** A change to what every app may do needs a sign-in within the last 10 minutes. */
    private void freshly(Instant authTime) {
        if (authTime == null || authTime.plus(Duration.ofMinutes(10)).isBefore(clock.instant())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REAUTH_REQUIRED", "Hãy đăng nhập lại trước khi đổi cấu hình app (trong vòng 10 phút).");
        }
    }

    private static Instant instant(String s) {
        return s == null || s.isBlank() ? null : Instant.parse(s);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
