package com.bonbon.backend.common.geo;

import java.util.List;
import java.util.Optional;

/**
 * Address lookup (reference/architecture/address-and-geocoding.md): suggestions while the user types, then
 * one place-detail call when an address is saved. Implementations throw {@link GeocodingUnavailableException}
 * when the provider cannot answer, so the caller can say "suggestions unavailable".
 */
public interface Geocoder {

    /**
     * @param nearLat optional bias towards the user's position (with {@code nearLng})
     */
    List<PlaceSuggestion> autocomplete(String input, Double nearLat, Double nearLng);

    /** Empty when the provider does not know {@code placeId}. */
    Optional<PlaceDetail> placeDetail(String placeId);
}
