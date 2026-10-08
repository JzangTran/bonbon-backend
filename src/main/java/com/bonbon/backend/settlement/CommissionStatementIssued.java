package com.bonbon.backend.settlement;

import java.time.Instant;
import java.util.UUID;

/** A statement for unpaid commission was issued to a shop (notifications listen; nothing in settlement depends on them). */
public record CommissionStatementIssued(UUID vendorId, UUID statementId, long amountDue, Instant dueAt) {
}
