package com.bonbon.backend.settlement;

import java.util.UUID;

/** A payout to a shop was recorded: the shop is told (notifications listen, nothing in settlement depends on them). */
public record PayoutRecorded(UUID vendorId, int amount, String reference) {
}
