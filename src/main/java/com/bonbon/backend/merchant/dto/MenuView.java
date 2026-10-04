package com.bonbon.backend.merchant.dto;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.MenuItemStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** The seller's whole menu: sections in display order, each with its dishes. */
@Schema(name = "MenuView")
public record MenuView(List<Section> sections) {

    @Schema(name = "MenuSectionView")
    public record Section(UUID id, String name, int sortOrder, List<Item> items) {
    }

    /** {@code soldOut} is the effective state (switched off, or stock at 0); {@code stockQuantity} null means unlimited. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MenuItemView")
    public record Item(
            UUID id,
            UUID sectionId,
            UUID categoryId,
            String name,
            String description,
            int price,
            String photoUrl,
            MenuItemStatus status,
            boolean soldOut,
            Integer stockQuantity,
            int sortOrder) {
    }
}
