package com.bonbon.backend.common.geo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Deterministic stand-in for tests and for local development without a Goong key: every input yields
 * three suggestions a few hundred metres apart around a fixed point, and the place id encodes the
 * suggestion so its detail can be rebuilt. Never active in production (see GeoConfig).
 */
class FakeGeocoder implements Geocoder {

    static final double ORIGIN_LAT = 21.0285;
    static final double ORIGIN_LNG = 105.8542;
    static final String WARD = "Phường Hoàn Kiếm";
    static final String PROVINCE = "Hà Nội";
    private static final String PREFIX = "fake:";

    @Override
    public List<PlaceSuggestion> autocomplete(String input, Double nearLat, Double nearLng) {
        String text = input.trim();
        return IntStream.rangeClosed(1, 3).mapToObj(n -> {
            String main = text + " (gợi ý " + n + ")";
            return new PlaceSuggestion(encode(main, n), main + ", " + WARD + ", " + PROVINCE, main, WARD + ", " + PROVINCE,
                    WARD, PROVINCE);
        }).toList();
    }

    @Override
    public Optional<PlaceDetail> placeDetail(String placeId) {
        if (placeId == null || !placeId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = placeId.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        int n;
        String main;
        try {
            n = Integer.parseInt(parts[0]);
            main = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // About 300 m apart, so radius checks behave like a real neighbourhood.
        double lat = ORIGIN_LAT + n * 0.0027;
        return Optional.of(new PlaceDetail(placeId, main + ", " + WARD + ", " + PROVINCE, main, lat, ORIGIN_LNG, WARD, PROVINCE));
    }

    private static String encode(String main, int n) {
        return PREFIX + n + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(main.getBytes(StandardCharsets.UTF_8));
    }
}
