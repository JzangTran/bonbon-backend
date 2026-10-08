package com.bonbon.backend.settlement;

import java.time.Instant;
import java.util.UUID;

/** A statement is about to fall due and still has an unpaid amount. */
public record CommissionStatementReminder(UUID vendorId, UUID statementId, long unpaid, Instant dueAt) {
}
