package com.bonbon.backend.support;

import java.time.Instant;
import java.util.UUID;

/**
 * An administrator decided a content report (review-reports.md). Notification tells the reporter whether action was taken, without
 * details about someone else's account, and, when action was taken, tells the affected person what and why and until when they can
 * contest it.
 *
 * @param reporterAudience {@code CUSTOMER} or {@code SHOP}, the side the report was filed from
 * @param affectedUserId   the author of the content or the shop's owner; null when nothing was done
 * @param affectedAudience {@code CUSTOMER} or {@code SHOP}
 */
public record ReportDecided(UUID reportId, UUID reporterUserId, String reporterAudience, boolean actioned, UUID affectedUserId,
        String affectedAudience, String targetType, String decisionCode, String reason, Instant contestUntil) {
}
