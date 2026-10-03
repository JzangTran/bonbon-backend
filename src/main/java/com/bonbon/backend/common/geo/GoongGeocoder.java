package com.bonbon.backend.common.geo;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Goong REST API V2 (help.goong.io): Autocomplete and Place Detail, which return the post-merger
 * administrative names. 5 s timeout and one retry; an outbound limit of 180 calls per minute, the lower of
 * the two figures Goong publishes, keeps the free quota and the provider's rate limit safe.
 */
class GoongGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(GoongGeocoder.class);
    private static final int OUTBOUND_PER_MINUTE = 180;
    private static final int SUGGESTION_LIMIT = 6;

    private final RestClient http;
    private final String apiKey;
    private final RateLimiter rateLimiter;

    GoongGeocoder(RestClient http, String apiKey, RateLimiter rateLimiter) {
        this.http = http;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<PlaceSuggestion> autocomplete(String input, Double nearLat, Double nearLng) {
        AutocompleteResponse response = call(() -> http.get()
                .uri(b -> {
                    b.path("/v2/place/autocomplete").queryParam("api_key", apiKey).queryParam("input", input)
                            .queryParam("limit", SUGGESTION_LIMIT).queryParam("more_compound", true);
                    if (nearLat != null && nearLng != null) {
                        b.queryParam("location", nearLat + "," + nearLng);
                    }
                    return b.build();
                })
                .retrieve().body(AutocompleteResponse.class));
        if (response == null || response.predictions() == null) {
            return List.of();
        }
        return response.predictions().stream()
                .filter(p -> p.placeId() != null)
                .map(p -> new PlaceSuggestion(p.placeId(), p.description(),
                        p.structuredFormatting() == null ? p.description() : p.structuredFormatting().mainText(),
                        p.structuredFormatting() == null ? null : p.structuredFormatting().secondaryText(),
                        p.compound() == null ? null : p.compound().commune(),
                        p.compound() == null ? null : p.compound().province()))
                .toList();
    }

    @Override
    public Optional<PlaceDetail> placeDetail(String placeId) {
        DetailResponse response = call(() -> http.get()
                .uri(b -> b.path("/v2/place/detail").queryParam("api_key", apiKey).queryParam("place_id", placeId).build())
                .retrieve().body(DetailResponse.class));
        if (response == null || response.result() == null || response.result().geometry() == null
                || response.result().geometry().location() == null) {
            return Optional.empty();
        }
        DetailResult r = response.result();
        return Optional.of(new PlaceDetail(r.placeId() == null ? placeId : r.placeId(), r.formattedAddress(), r.name(),
                r.geometry().location().lat(), r.geometry().location().lng(),
                r.compound() == null ? null : r.compound().commune(),
                r.compound() == null ? null : r.compound().province()));
    }

    private <T> T call(Supplier<T> request) {
        if (!rateLimiter.tryAcquire("goong:outbound", OUTBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            log.warn("Goong outbound limit reached");
            throw new GeocodingUnavailableException();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (RestClientException e) {
                if (attempt == 2) {
                    log.warn("Goong request failed: {}", e.getMessage());
                    throw new GeocodingUnavailableException();
                }
            }
        }
    }

    record AutocompleteResponse(List<Prediction> predictions, String status) {
    }

    record Prediction(String description, @JsonProperty("place_id") String placeId,
            @JsonProperty("structured_formatting") Formatting structuredFormatting, Compound compound) {
    }

    record Formatting(@JsonProperty("main_text") String mainText, @JsonProperty("secondary_text") String secondaryText) {
    }

    record Compound(String commune, String district, String province) {
    }

    record DetailResponse(DetailResult result, String status) {
    }

    record DetailResult(@JsonProperty("place_id") String placeId, @JsonProperty("formatted_address") String formattedAddress,
            String name, Geometry geometry, Compound compound) {
    }

    record Geometry(Location location) {
    }

    record Location(double lat, double lng) {
    }
}
