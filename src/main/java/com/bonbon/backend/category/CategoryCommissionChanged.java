package com.bonbon.backend.category;

import java.math.BigDecimal;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;

/**
 * Published in the transaction that changes a category's own commission rate. {@code rate} is null when the node
 * went back to inheriting; {@code previousRate} is null when it had none. Settlement keeps the history from it.
 */
public record CategoryCommissionChanged(UUID categoryId, String categoryName, BigDecimal rate, BigDecimal previousRate, ActorType by,
        UUID actorId) {
}
