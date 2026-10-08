package com.bonbon.backend.settlement;

import java.time.Instant;
import java.util.UUID;

/**
 * Overdue commission moved a shop to another stage: {@code OVERDUE} (the notice of what comes next), {@code RESTRICTED},
 * {@code PAUSED}, {@code REVIEW} (flagged for an administrator) or {@code NONE} (paid, everything lifted).
 * {@code nextAt} is when the next step may apply, never earlier than 5 days after this notice.
 */
public record CommissionStageChanged(UUID vendorId, String stage, long overdueAmount, Instant nextAt) {
}
