package com.bonbon.backend.common.geo;

/** One autocomplete row: what the user picks. {@code ward} and {@code province} use the post-merger names. */
public record PlaceSuggestion(String placeId, String description, String mainText, String secondaryText, String ward,
        String province) {
}
