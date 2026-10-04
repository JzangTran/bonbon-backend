package com.bonbon.backend.merchant.dto;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.OptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Requests of a shop managing option groups (manage-menu-options.md). */
public final class OptionRequests {

    private OptionRequests() {
    }

    /**
     * The whole group. On update, options with an {@code id} are changed, options without one are added,
     * and existing options left out are archived.
     */
    @Schema(name = "OptionGroupRequest")
    public record Group(
            @NotBlank @Size(max = 60) String name,
            @NotNull @PositiveOrZero @Max(30) Integer min,
            @NotNull @Max(30) Integer max,
            @NotEmpty @Size(max = 30) List<@Valid @NotNull Option> options) {
    }

    /** {@code status} is AVAILABLE (default) or SOLD_OUT. */
    @Schema(name = "OptionRequest")
    public record Option(
            UUID id,
            @NotBlank @Size(max = 60) String name,
            @NotNull @PositiveOrZero @Max(1_000_000) Integer priceDelta,
            Boolean defaultChoice,
            OptionStatus status) {
    }

    @Schema(name = "OptionStatusRequest")
    public record Status(@NotNull OptionStatus status) {
    }

    /** The complete list of groups a dish offers, in display order. */
    @Schema(name = "MenuItemOptionGroupsRequest")
    public record ItemGroups(@NotNull @Size(max = 10) List<@NotNull UUID> groupIds) {
    }
}
