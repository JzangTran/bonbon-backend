package com.bonbon.backend.common.geo;

import java.util.List;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/** Request shape and response parsing against the documented Goong V2 payloads (help.goong.io). */
class GoongGeocoderTests {

    MockRestServiceServer server;
    RateLimiter rateLimiter;
    GoongGeocoder goong;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://rsapi.goong.io");
        server = MockRestServiceServer.bindTo(builder).build();
        rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        goong = new GoongGeocoder(builder.build(), "test-key", rateLimiter);
    }

    @Test
    void autocompleteSendsTheKeyAndMapsSuggestions() {
        server.expect(requestTo(startsWith("https://rsapi.goong.io/v2/place/autocomplete")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("api_key", "test-key"))
                .andExpect(queryParam("input", "vinhomes%20s2"))
                .andExpect(queryParam("location", "21.0,105.8"))
                .andRespond(withSuccess("""
                        {"predictions":[{"description":"Toà S2, Vinhomes, Phường Tây Mỗ, Hà Nội","place_id":"abc123",
                          "structured_formatting":{"main_text":"Toà S2","secondary_text":"Phường Tây Mỗ, Hà Nội"},
                          "compound":{"commune":"Phường Tây Mỗ","province":"Hà Nội"},"distance_meters":120}],
                         "status":"OK"}""", MediaType.APPLICATION_JSON));

        List<PlaceSuggestion> result = goong.autocomplete("vinhomes s2", 21.0, 105.8);

        assertThat(result).containsExactly(new PlaceSuggestion("abc123", "Toà S2, Vinhomes, Phường Tây Mỗ, Hà Nội",
                "Toà S2", "Phường Tây Mỗ, Hà Nội", "Phường Tây Mỗ", "Hà Nội"));
        server.verify();
    }

    @Test
    void placeDetailMapsCoordinatesAndNames() {
        server.expect(requestTo(startsWith("https://rsapi.goong.io/v2/place/detail")))
                .andExpect(queryParam("place_id", "abc123"))
                .andRespond(withSuccess("""
                        {"result":{"place_id":"abc123","formatted_address":"Toà S2, Phường Tây Mỗ, Hà Nội","name":"Toà S2",
                          "geometry":{"location":{"lat":21.0105,"lng":105.7461}},
                          "compound":{"commune":"Phường Tây Mỗ","province":"Hà Nội"}},"status":"OK"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(goong.placeDetail("abc123")).hasValue(new PlaceDetail("abc123", "Toà S2, Phường Tây Mỗ, Hà Nội",
                "Toà S2", 21.0105, 105.7461, "Phường Tây Mỗ", "Hà Nội"));
    }

    @Test
    void unknownPlaceIsEmpty() {
        server.expect(requestTo(startsWith("https://rsapi.goong.io/v2/place/detail")))
                .andRespond(withSuccess("{\"status\":\"NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        assertThat(goong.placeDetail("nope")).isEmpty();
    }

    @Test
    void retriesOnceThenReportsUnavailable() {
        server.expect(times(2), requestTo(startsWith("https://rsapi.goong.io/v2/place/autocomplete")))
                .andRespond(withServerError());
        assertThatThrownBy(() -> goong.autocomplete("abc", null, null)).isInstanceOf(GeocodingUnavailableException.class);
        server.verify();
    }

    @Test
    void outboundLimitStopsCallsBeforeTheyLeave() {
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(false);
        assertThatThrownBy(() -> goong.placeDetail("abc123")).isInstanceOf(GeocodingUnavailableException.class);
        server.verify();
    }

    @Test
    void haversineMatchesAKnownDistance() {
        // Hoàn Kiếm Lake to Hà Nội Opera House: about 0.74 km.
        assertThat(GeoDistance.haversineKm(21.0287, 105.8524, 21.0243, 105.8577)).isBetween(0.7, 0.8);
        assertThat(GeoDistance.haversineKm(21.0, 105.8, 21.0, 105.8)).isZero();
    }
}
