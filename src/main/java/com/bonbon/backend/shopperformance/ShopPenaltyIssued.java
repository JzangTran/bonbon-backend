package com.bonbon.backend.shopperformance;

import java.time.LocalDate;
import java.util.UUID;

/** A weekly evaluation gave the shop a penalty point: {@code activePoints} is what it has now, {@code ratePercent} the week's failure rate. */
public record ShopPenaltyIssued(UUID vendorId, int points, int activePoints, double ratePercent, LocalDate weekStart) {
}
