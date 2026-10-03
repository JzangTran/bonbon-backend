package com.bonbon.backend.merchant.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.merchant.entity.OpeningWindow;
import org.springframework.http.HttpStatus;

/**
 * Validation shared by the wizard and the store-info screen (edit-store-info.md): at most a handful of windows
 * per day, and no two windows overlapping anywhere in the week, counting windows that run past midnight
 * (and Sunday night into Monday).
 */
final class OpeningHoursRules {

    static final int MAX_WINDOWS_PER_DAY = 4;
    private static final int WEEK_MINUTES = 7 * 1440;

    private OpeningHoursRules() {
    }

    static void validate(List<OpeningWindow> windows) {
        Map<Short, Long> perDay = windows.stream().collect(Collectors.groupingBy(OpeningWindow::weekday, Collectors.counting()));
        if (perDay.values().stream().anyMatch(n -> n > MAX_WINDOWS_PER_DAY)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "OPENING_HOURS_TOO_MANY",
                    "Mỗi ngày tối đa " + MAX_WINDOWS_PER_DAY + " khung giờ.");
        }
        List<int[]> spans = new ArrayList<>();
        for (OpeningWindow w : windows) {
            int start = w.startMinute();
            int end = w.endMinute();
            if (end > WEEK_MINUTES) {
                spans.add(new int[] {start, WEEK_MINUTES});
                spans.add(new int[] {0, end - WEEK_MINUTES});
            } else {
                spans.add(new int[] {start, end});
            }
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i)[0] < spans.get(i - 1)[1]) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "OPENING_HOURS_OVERLAP",
                        "Các khung giờ mở cửa bị chồng lên nhau.");
            }
        }
    }
}
