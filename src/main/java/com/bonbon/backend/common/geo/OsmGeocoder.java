package com.bonbon.backend.common.geo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenStreetMap data through Photon, which is made for search as you type (the public Nominatim forbids autocomplete). Photon has
 * no lookup by id, so the {@code placeId} carries the place itself: picking a suggestion costs no second call.
 */
class OsmGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(OsmGeocoder.class);
    private static final int OUTBOUND_PER_MINUTE = 60;
    private static final int SUGGESTION_LIMIT = 6;
    private static final String VIETNAM_BOX = "102.1,8.2,109.6,23.5";
    private static final String PREFIX = "osm1.";
    private static final String SEPARATOR = "\u001f";

    private final RestClient http;
    private final RateLimiter rateLimiter;

    OsmGeocoder(RestClient http, RateLimiter rateLimiter) {
        this.http = http;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<PlaceSuggestion> autocomplete(String input, Double nearLat, Double nearLng) {
        Response response = call(() -> http.get().uri(b -> {
            b.path("/api").queryParam("q", input).queryParam("limit", SUGGESTION_LIMIT).queryParam("bbox", VIETNAM_BOX);
            if (nearLat != null && nearLng != null) {
                b.queryParam("lat", nearLat).queryParam("lon", nearLng);
            }
            return b.build();
        }).retrieve().body(Response.class));
        return features(response).map(OsmGeocoder::detail)
                .map(d -> new PlaceSuggestion(encode(d), d.formattedAddress(), d.name(), secondary(d), d.ward(), d.province()))
                .toList();
    }

    @Override
    public Optional<PlaceDetail> placeDetail(String placeId) {
        return decode(placeId);
    }

    @Override
    public Optional<PlaceDetail> reverse(double lat, double lng) {
        Response response = call(() -> http.get().uri(b -> b.path("/reverse").queryParam("lat", lat).queryParam("lon", lng).build())
                .retrieve().body(Response.class));
        return features(response).findFirst().map(OsmGeocoder::detail).map(d -> {
            PlaceDetail atPin = new PlaceDetail(null, d.formattedAddress(), d.name(), lat, lng, d.ward(), d.province());
            return new PlaceDetail(encode(atPin), atPin.formattedAddress(), atPin.name(), lat, lng, atPin.ward(), atPin.province());
        });
    }

    private static Stream<Feature> features(Response response) {
        return response == null || response.features() == null ? Stream.empty()
                : response.features().stream().filter(f -> f.geometry() != null && f.properties() != null
                        && f.geometry().coordinates() != null && f.geometry().coordinates().size() == 2
                        && "VN".equalsIgnoreCase(f.properties().countrycode()));
    }

    private static PlaceDetail detail(Feature f) {
        Properties p = f.properties();
        String street = Stream.of(p.housenumber(), p.street()).filter(Objects::nonNull).collect(Collectors.joining(" "));
        String name = p.name() != null ? p.name() : street.isEmpty() ? firstOf(p.district(), p.city(), p.state()) : street;
        String province = firstOf(p.state(), p.city());
        String address = Stream.of(name, street, p.locality(), p.district(), p.city(), p.state())
                .filter(part -> part != null && !part.isBlank()).distinct().collect(Collectors.joining(", "));
        return new PlaceDetail(null, address, name, f.geometry().coordinates().get(1), f.geometry().coordinates().get(0),
                p.district(), province);
    }

    private static String firstOf(String... values) {
        return Arrays.stream(values).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String secondary(PlaceDetail d) {
        String prefix = d.name() + ", ";
        return d.formattedAddress().startsWith(prefix) ? d.formattedAddress().substring(prefix.length()) : d.formattedAddress();
    }

    private static String encode(PlaceDetail d) {
        String raw = String.join(SEPARATOR, Double.toString(d.lat()), Double.toString(d.lng()), blank(d.name()), blank(d.formattedAddress()),
                blank(d.ward()), blank(d.province()));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<PlaceDetail> decode(String placeId) {
        if (placeId == null || !placeId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(placeId.substring(PREFIX.length())), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR, -1);
            double lat = Double.parseDouble(parts[0]);
            double lng = Double.parseDouble(parts[1]);
            if (parts.length != 6 || lat < 6 || lat > 24 || lng < 102 || lng > 118 || parts[3].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PlaceDetail(placeId, parts[3], empty(parts[2]), lat, lng, empty(parts[4]), empty(parts[5])));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String empty(String value) {
        return value.isEmpty() ? null : value;
    }

    private <T> T call(Supplier<T> request) {
        if (!rateLimiter.tryAcquire("osm:outbound", OUTBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            log.warn("OSM geocoder outbound limit reached");
            throw new GeocodingUnavailableException();
        }
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("OSM geocoder request failed: {}", e.getMessage());
            throw new GeocodingUnavailableException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<Feature> features) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(Geometry geometry, Properties properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geometry(List<Double> coordinates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(String name, String housenumber, String street, String locality, String district, String city, String state,
            String countrycode) {
    }
}
