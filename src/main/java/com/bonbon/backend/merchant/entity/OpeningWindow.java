package com.bonbon.backend.merchant.entity;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One opening window on an ISO weekday (1 = Monday) in Vietnam time. {@code closesAt} before
 * {@code opensAt} means the window runs past midnight; it belongs to the day it starts on.
 */
@Embeddable
public record OpeningWindow(
        @Column(name = "weekday", nullable = false) short weekday,
        @Column(name = "opens_at", nullable = false) LocalTime opensAt,
        @Column(name = "closes_at", nullable = false) LocalTime closesAt) {

    public boolean crossesMidnight() {
        return closesAt.isBefore(opensAt);
    }

    /** Minutes since Monday 00:00, end exclusive; a window past midnight ends on the next day. */
    public int startMinute() {
        return (weekday - 1) * 1440 + opensAt.getHour() * 60 + opensAt.getMinute();
    }

    public int endMinute() {
        int end = (weekday - 1) * 1440 + closesAt.getHour() * 60 + closesAt.getMinute();
        return crossesMidnight() ? end + 1440 : end;
    }
}
