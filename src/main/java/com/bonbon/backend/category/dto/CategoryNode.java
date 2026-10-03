package com.bonbon.backend.category.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One node of the tree with its children. {@code commissionRate} is the node's own rate (null = inherited);
 * {@code effectiveCommissionRate} is what applies after inheritance (null = the global default).
 */
public record CategoryNode(UUID id, UUID parentId, int level, String name, int sortOrder, boolean active,
        BigDecimal commissionRate, BigDecimal effectiveCommissionRate, List<CategoryNode> children) {
}
