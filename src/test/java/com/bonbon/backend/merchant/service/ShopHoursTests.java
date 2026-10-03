package com.bonbon.backend.merchant.service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import com.bonbon.backend.merchant.entity.OpeningWindow;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShopHoursTests {

    // 2026-10-05 is a Monday.
    static ZonedDateTime vn(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, 4 + day, hour, minute).atZone(ShopHours.VIETNAM);
    }

    static OpeningWindow w(int weekday, String opens, String closes) {
        return new OpeningWindow((short) weekday, LocalTime.parse(opens), LocalTime.parse(closes));
    }

    @Test
    void openIncludesTheStartAndExcludesTheEnd() {
        List<OpeningWindow> hours = List.of(w(1, "06:00", "10:00"));
        assertThat(ShopHours.withinHours(hours, vn(1, 6, 0))).isTrue();
        assertThat(ShopHours.withinHours(hours, vn(1, 9, 59))).isTrue();
        assertThat(ShopHours.withinHours(hours, vn(1, 10, 0))).isFalse();
        assertThat(ShopHours.withinHours(hours, vn(2, 7, 0))).isFalse();
    }

    @Test
    void aWindowPastMidnightBelongsToTheDayItStarts() {
        List<OpeningWindow> hours = List.of(w(5, "18:00", "02:00"));
        assertThat(ShopHours.withinHours(hours, vn(5, 23, 30))).isTrue();
        assertThat(ShopHours.withinHours(hours, vn(6, 1, 30))).isTrue();
        assertThat(ShopHours.withinHours(hours, vn(6, 2, 0))).isFalse();
        assertThat(ShopHours.withinHours(hours, vn(5, 1, 30))).isFalse();
    }

    @Test
    void sundayNightRunsIntoMonday() {
        List<OpeningWindow> hours = List.of(w(7, "22:00", "03:00"));
        assertThat(ShopHours.withinHours(hours, vn(8, 2, 0))).isTrue();
        assertThat(ShopHours.withinHours(hours, vn(8, 4, 0))).isFalse();
    }

    @Test
    void timeIsVietnamTimeWhateverTheServerZone() {
        List<OpeningWindow> hours = List.of(w(1, "06:00", "10:00"));
        // 23:30 UTC on Sunday is 06:30 on Monday in Vietnam.
        ZonedDateTime utc = LocalDateTime.of(2026, 10, 4, 23, 30).atZone(ZoneOffset.UTC);
        assertThat(ShopHours.withinHours(hours, utc)).isTrue();
    }
}
