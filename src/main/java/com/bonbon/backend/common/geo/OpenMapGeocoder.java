package com.bonbon.backend.common.geo;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OpenMap.vn REST API (docs.openmap.vn), in its Google-style shape and with {@code admin_v2} for the post-2025 wards and provinces.
 * Autocomplete is free and Place Detail is billed per call. The administrative levels come as unlabelled
 * {@code address_components}: the last is the province, and the ward is the one named phường, xã, thị trấn or đặc khu.
 */
class OpenMapGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(OpenMapGeocoder.class);
    private static final int OUTBOUND_PER_MINUTE = 180;
    private static final List<String> WARD_PREFIXES = List.of("phường ", "xã ", "thị trấn ", "đặc khu ");

    private final RestClient http;
    private final String apiKey;
    private final RateLimiter rateLimiter;

    OpenMapGeocoder(RestClient http, String apiKey, RateLimiter rateLimiter) {
        this.http = http;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<PlaceSuggestion> autocomplete(String input, Double nearLat, Double nearLng) {
        AutocompleteResponse response = call(() -> http.get().uri(b -> {
            b.path("/autocomplete").queryParam("apikey", apiKey).queryParam("input", input).queryParam("admin_v2", true);
            if (nearLat != null && nearLng != null) {
                b.queryParam("location", nearLat + "," + nearLng);
            }
            return b.build();
        }).retrieve().body(AutocompleteResponse.class));
        if (response == null || response.predictions() == null) {
            return List.of();
        }
        return response.predictions().stream().filter(p -> p.placeId() != null).map(p -> {
            Formatting f = p.structuredFormatting();
            List<String> terms = p.terms() == null ? List.of() : p.terms().stream().map(Term::value).toList();
            return new PlaceSuggestion(p.placeId(), tidy(p.description()), tidy(f == null ? p.description() : f.mainText()),
                    tidy(f == null ? null : f.secondaryText()), ward(terms), province(terms));
        }).toList();
    }

    @Override
    public Optional<PlaceDetail> placeDetail(String placeId) {
        DetailResponse response = call(() -> http.get()
                .uri(b -> b.path("/place").queryParam("apikey", apiKey).queryParam("ids", placeId).queryParam("format", "google")
                        .queryParam("admin_v2", true).build())
                .retrieve().body(DetailResponse.class));
        return response == null ? Optional.empty() : detail(response.result(), placeId, null);
    }

    @Override
    public Optional<PlaceDetail> reverse(double lat, double lng) {
        ReverseResponse response = call(() -> http.get()
                .uri(b -> b.path("/geocode/reverse").queryParam("apikey", apiKey).queryParam("latlng", lat + "," + lng)
                        .queryParam("admin_v2", true).build())
                .retrieve().body(ReverseResponse.class));
        if (response == null || response.results() == null || response.results().isEmpty()) {
            return Optional.empty();
        }
        // the pin stays where the user dropped it, not on the place found nearby
        return detail(response.results().get(0), null, new double[] {lat, lng});
    }

    private static Optional<PlaceDetail> detail(Result r, String placeId, double[] at) {
        if (r == null || r.formattedAddress() == null || (at == null && (r.geometry() == null || r.geometry().location() == null))) {
            return Optional.empty();
        }
        List<String> names = r.addressComponents() == null ? List.of() : r.addressComponents().stream().map(Component::longName).toList();
        double lat = at == null ? r.geometry().location().lat() : at[0];
        double lng = at == null ? r.geometry().location().lng() : at[1];
        return Optional.of(new PlaceDetail(r.placeId() == null ? placeId : r.placeId(), tidy(r.formattedAddress()), r.name(), lat, lng,
                ward(names), province(names)));
    }

    /** "phường Phú Thượng" as "Phú Thượng". */
    private static String ward(List<String> levels) {
        for (String level : levels) {
            String lower = level.toLowerCase(Locale.ROOT);
            for (String prefix : WARD_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    return level.substring(prefix.length()).strip();
                }
            }
        }
        return null;
    }

    /**
     * The level named "thành phố …" or "tỉnh …", without its kind; null when there is none (some places end on a road name or on
     * nothing at all).
     */
    private static String province(List<String> levels) {
        for (int i = levels.size() - 1; i >= 0; i--) {
            String level = levels.get(i).strip();
            String lower = level.toLowerCase(Locale.ROOT);
            for (String kind : List.of("thành phố ", "tỉnh ")) {
                if (lower.startsWith(kind)) {
                    return level.substring(kind.length()).strip();
                }
            }
        }
        return null;
    }

    /** "Vinhomes Smart City,  " as "Vinhomes Smart City": some entries have empty trailing levels. */
    private static String tidy(String text) {
        return text == null ? null : text.replaceAll("(,\\s*)+$", "").strip();
    }

    private <T> T call(Supplier<T> request) {
        if (!rateLimiter.tryAcquire("openmap:outbound", OUTBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            log.warn("OpenMap outbound limit reached");
            throw new GeocodingUnavailableException();
        }
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("OpenMap request failed: {}", e.getMostSpecificCause().getMessage());
            throw new GeocodingUnavailableException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutocompleteResponse(List<Prediction> predictions, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Prediction(String description, @JsonProperty("place_id") String placeId,
            @JsonProperty("structured_formatting") Formatting structuredFormatting, List<Term> terms) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Formatting(@JsonProperty("main_text") String mainText, @JsonProperty("secondary_text") String secondaryText) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Term(String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetailResponse(Result result, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReverseResponse(List<Result> results, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Result(@JsonProperty("place_id") String placeId, @JsonProperty("formatted_address") String formattedAddress, String name,
            Geometry geometry, @JsonProperty("address_components") List<Component> addressComponents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Component(@JsonProperty("long_name") String longName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geometry(Location location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Location(double lat, double lng) {
    }
}
