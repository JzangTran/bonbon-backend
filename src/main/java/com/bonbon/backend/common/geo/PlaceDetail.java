package com.bonbon.backend.common.geo;

/** A picked place, resolved once and stored with the address (never re-fetched at read time). */
public record PlaceDetail(String placeId, String formattedAddress, String name, double lat, double lng, String ward,
        String province) {
}
