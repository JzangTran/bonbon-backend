package com.bonbon.backend.merchant.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Requests of a shop managing its menu (manage-menu.md, manage-menu-categories.md). */
public final class MenuRequests {

    private MenuRequests() {
    }

    @Schema(name = "MenuSectionRequest")
    public record Section(@NotBlank @Size(max = 60) String name) {
    }

    /** The shop's complete list of ids, in the new display order. */
    @Schema(name = "MenuOrderRequest")
    public record Order(@NotEmpty @Size(max = 200) List<@NotNull UUID> ids) {
    }

    @Schema(name = "MenuItemCreateRequest")
    public record ItemCreate(
            @NotNull UUID sectionId,
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotNull @PositiveOrZero @Max(10_000_000) Integer price,
            @PositiveOrZero @Max(100_000) Integer stockQuantity) {
    }

    /**
     * Only the fields present change. {@code clearDescription} / {@code clearStock} remove those optional
     * values (empty stock means unlimited).
     */
    @Schema(name = "MenuItemUpdateRequest")
    public record ItemUpdate(
            UUID sectionId,
            UUID categoryId,
            @Size(min = 1, max = 100) String name,
            @Size(max = 500) String description,
            @PositiveOrZero @Max(10_000_000) Integer price,
            @PositiveOrZero @Max(100_000) Integer stockQuantity,
            Boolean clearDescription,
            Boolean clearStock) {
    }
}
