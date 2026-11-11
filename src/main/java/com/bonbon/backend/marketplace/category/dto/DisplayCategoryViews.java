package com.bonbon.backend.marketplace.category.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** The category strip of the home page: what the app shows, and what administrators set. */
public final class DisplayCategoryViews {

    private DisplayCategoryViews() {
    }

    /** One picture of the strip; {@code imageUrl} null until the administrator adds a picture. */
    @Schema(name = "DisplayCategory")
    public record Item(UUID id, String name, String imageUrl) {
    }

    /** The administrator's view: also the leaf categories it gathers, its place and whether it shows. */
    @Schema(name = "DisplayCategoryAdmin")
    public record Admin(UUID id, String name, String imageUrl, int sortOrder, boolean active, List<Leaf> leaves) {
    }

    @Schema(name = "DisplayCategoryLeaf")
    public record Leaf(UUID id, String name) {
    }

    @Schema(name = "DisplayCategoryCreateRequest")
    public record Create(
            @NotBlank @Size(max = 40) String name,
            @Schema(description = "Ngành cấp 3 được gom vào danh mục này.") @NotEmpty @Size(max = 100) Set<@NotNull UUID> leafIds) {
    }

    /** Only the fields present change. */
    @Schema(name = "DisplayCategoryUpdateRequest")
    public record Update(
            @Size(min = 1, max = 40) String name,
            @Size(min = 1, max = 100) Set<@NotNull UUID> leafIds,
            @PositiveOrZero @Max(10_000) Integer sortOrder,
            Boolean active) {
    }
}
