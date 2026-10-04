package com.bonbon.backend.merchant.dto;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.OptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The shop's option groups, each with its live options and the dishes it is attached to. */
@Schema(name = "OptionGroupsView")
public record OptionGroupsView(List<Group> groups) {

    @Schema(name = "OptionGroupView")
    public record Group(UUID id, String name, int min, int max, List<Option> options, List<UUID> menuItemIds) {
    }

    @Schema(name = "OptionView")
    public record Option(UUID id, String name, int priceDelta, OptionStatus status, boolean defaultChoice, int displayOrder) {
    }
}
