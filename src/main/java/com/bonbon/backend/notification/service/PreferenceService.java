package com.bonbon.backend.notification.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.notification.dto.PreferenceRequests;
import com.bonbon.backend.notification.dto.PreferenceViews;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each user chose to receive (flows/account/manage-notification-preferences.md). The in-app list is never affected: a
 * silenced push is still in the app. A locked category cannot be written and is always allowed.
 */
@Service
public class PreferenceService {

    private static final String DEFAULT_ZONE = "Asia/Ho_Chi_Minh";

    private final JdbcClient jdbc;
    private final Clock clock;

    PreferenceService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreferenceViews.Settings view(CurrentPrincipal caller) {
        String role = role(caller);
        Map<String, Boolean> stored = stored(caller.id());
        List<PreferenceViews.Category> categories = new ArrayList<>();
        for (NotificationCategory c : NotificationCategory.values()) {
            if (!c.forRole(role)) {
                continue;
            }
            List<PreferenceViews.Channel> channels = new ArrayList<>();
            c.defaults().forEach((channel, def) -> channels.add(new PreferenceViews.Channel(channel, c.locked() || stored.getOrDefault(key(c, channel), def), def)));
            categories.add(new PreferenceViews.Category(c.name(), c.title(), c.description(), c.locked(), channels));
        }
        return new PreferenceViews.Settings(categories, quietHours(caller.id()));
    }

    @Transactional
    public PreferenceViews.Settings update(CurrentPrincipal caller, PreferenceRequests.Update request) {
        String role = role(caller);
        record Checked(NotificationCategory category, String channel, boolean enabled) {
        }
        List<Checked> checked = new ArrayList<>();
        for (PreferenceRequests.Change change : request.changes()) {
            NotificationCategory category = category(change.category());
            if (!category.forRole(role)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_CATEGORY", "Nhóm thông báo không dành cho vai trò đang dùng.");
            }
            if (category.locked()) {
                throw BusinessException.unprocessable("CATEGORY_LOCKED", "Nhóm \"" + category.title() + "\" không thể tắt.");
            }
            if (!category.defaults().containsKey(change.channel())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CHANNEL_NOT_AVAILABLE", "Nhóm này không có kênh " + change.channel() + ".");
            }
            checked.add(new Checked(category, change.channel(), change.enabled()));
        }
        for (Checked c : checked) {
            if (c.enabled() == c.category().defaults().get(c.channel())) {
                jdbc.sql("delete from notification_preferences where user_id = :u and category = :c and channel = :ch")
                        .param("u", caller.id()).param("c", c.category().name()).param("ch", c.channel()).update();
            } else {
                jdbc.sql("""
                        insert into notification_preferences (user_id, category, channel, enabled, updated_at) values (:u, :c, :ch, :e, :now)
                        on conflict (user_id, category, channel) do update set enabled = excluded.enabled, updated_at = excluded.updated_at""")
                        .param("u", caller.id()).param("c", c.category().name()).param("ch", c.channel()).param("e", c.enabled())
                        .param("now", Timestamp.from(clock.instant())).update();
            }
        }
        return view(caller);
    }

    @Transactional
    public PreferenceViews.Settings setQuietHours(CurrentPrincipal caller, PreferenceRequests.QuietHours request) {
        role(caller);
        boolean none = request.start() == null && request.end() == null;
        if (none) {
            jdbc.sql("delete from notification_quiet_hours where user_id = :u").param("u", caller.id()).update();
            return view(caller);
        }
        if (request.start() == null || request.end() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QUIET_HOURS_INCOMPLETE", "Cần cả giờ bắt đầu và giờ kết thúc.");
        }
        LocalTime start = LocalTime.parse(request.start());
        LocalTime end = LocalTime.parse(request.end());
        if (start.equals(end)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QUIET_HOURS_EMPTY", "Giờ bắt đầu và kết thúc không được trùng nhau.");
        }
        String zone = request.timeZone() == null || request.timeZone().isBlank() ? DEFAULT_ZONE : request.timeZone().strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TIME_ZONE_INVALID", "Múi giờ không hợp lệ.");
        }
        jdbc.sql("""
                insert into notification_quiet_hours (user_id, start_time, end_time, time_zone, updated_at) values (:u, :s, :e, :z, :now)
                on conflict (user_id) do update set start_time = excluded.start_time, end_time = excluded.end_time, time_zone = excluded.time_zone, updated_at = excluded.updated_at""")
                .param("u", caller.id()).param("s", java.sql.Time.valueOf(start)).param("e", java.sql.Time.valueOf(end)).param("z", zone)
                .param("now", Timestamp.from(clock.instant())).update();
        return view(caller);
    }

    /** Whether a notification of this category may reach the user on this channel right now. */
    @Transactional(readOnly = true)
    public boolean allows(UUID userId, NotificationCategory category, String channel) {
        if (category.locked()) {
            return true;
        }
        Boolean def = category.defaults().get(channel);
        if (def == null) {
            return false;
        }
        boolean enabled = jdbc.sql("select enabled from notification_preferences where user_id = :u and category = :c and channel = :ch")
                .param("u", userId).param("c", category.name()).param("ch", channel).query(Boolean.class).optional().orElse(def);
        return enabled && !("PUSH".equals(channel) && inQuietHours(userId));
    }

    // --- internals

    private boolean inQuietHours(UUID userId) {
        return jdbc.sql("select start_time, end_time, time_zone from notification_quiet_hours where user_id = :u").param("u", userId)
                .query((rs, n) -> {
                    LocalTime now = clock.instant().atZone(ZoneId.of(rs.getString("time_zone"))).toLocalTime();
                    LocalTime start = rs.getTime("start_time").toLocalTime();
                    LocalTime end = rs.getTime("end_time").toLocalTime();
                    return start.isBefore(end) ? !now.isBefore(start) && now.isBefore(end) : !now.isBefore(start) || now.isBefore(end);
                }).optional().orElse(false);
    }

    private PreferenceViews.QuietHours quietHours(UUID userId) {
        return jdbc.sql("select start_time, end_time, time_zone from notification_quiet_hours where user_id = :u").param("u", userId)
                .query((rs, n) -> new PreferenceViews.QuietHours(rs.getTime("start_time").toLocalTime().toString(), rs.getTime("end_time").toLocalTime().toString(),
                        rs.getString("time_zone")))
                .optional().orElse(null);
    }

    private Map<String, Boolean> stored(UUID userId) {
        Map<String, Boolean> map = new HashMap<>();
        jdbc.sql("select category, channel, enabled from notification_preferences where user_id = :u").param("u", userId)
                .query((rs, n) -> map.put(rs.getString("category") + "/" + rs.getString("channel"), rs.getBoolean("enabled"))).list();
        return map;
    }

    private static String key(NotificationCategory c, String channel) {
        return c.name() + "/" + channel;
    }

    private static NotificationCategory category(String name) {
        try {
            return NotificationCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_CATEGORY", "Không có nhóm thông báo \"" + name + "\".");
        }
    }

    private static String role(CurrentPrincipal caller) {
        if (!"CUSTOMER".equals(caller.activeRole()) && !"SELLER".equals(caller.activeRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NOT_A_RECIPIENT", "Chỉ khách và người bán nhận thông báo.");
        }
        return caller.activeRole();
    }
}
