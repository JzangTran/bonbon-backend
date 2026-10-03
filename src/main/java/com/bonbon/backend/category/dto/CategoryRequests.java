package com.bonbon.backend.category.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class CategoryRequests {

    private CategoryRequests() {
    }

    /** A new level-2 or level-3 category under {@code parentId}; the root is fixed. */
    public record Create(
            @NotNull UUID parentId,
            @NotBlank @Size(max = 100) String name,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal commissionRate) {
    }

    /**
     * Only the fields present change. {@code clearCommissionRate} makes the node inherit again (a JSON null
     * for the rate cannot be told apart from "not sent"). {@code parentId} moves the node to another parent
     * of the same level.
     */
    public record Update(
            @Size(min = 1, max = 100) String name,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal commissionRate,
            Boolean clearCommissionRate,
            Boolean active,
            @PositiveOrZero Integer sortOrder,
            UUID parentId) {
    }
}
