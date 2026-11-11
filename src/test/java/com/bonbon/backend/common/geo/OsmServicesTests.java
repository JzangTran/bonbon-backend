package com.bonbon.backend.common.geo;

import java.util.List;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request shape and parsing against real Photon and OSRM payloads. */
class OsmServicesTests {

    RestClient.Builder builder = RestClient.builder().baseUrl("https://osm.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RateLimiter limiter = mock(RateLimiter.class);

    @BeforeEach
    void allow() {
        when(limiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
    }

    @Test
    void aSuggestionCarriesThePlaceSoPickingItNeedsNoSecondCall() {
        server.expect(requestTo(startsWith("https://osm.test/api?q="))).andRespond(withSuccess("""
                {"features":[
                 {"properties":{"name":"Vinhomes Smart City","district":"Tây Mỗ","city":"Hà Nội","countrycode":"VN"},"geometry":{"coordinates":[105.73,21.0]}},
                 {"properties":{"name":"Abroad","countrycode":"LA"},"geometry":{"coordinates":[102.6,17.9]}}]}""", MediaType.APPLICATION_JSON));
        OsmGeocoder geocoder = new OsmGeocoder(builder.build(), limiter);

        List<PlaceSuggestion> found = geocoder.autocomplete("vinhomes", null, null);

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.mainText()).isEqualTo("Vinhomes Smart City");
            assertThat(s.description()).isEqualTo("Vinhomes Smart City, Tây Mỗ, Hà Nội");
        });
        PlaceDetail detail = geocoder.placeDetail(found.get(0).placeId()).orElseThrow();
        assertThat(detail.lat()).isEqualTo(21.0);
        assertThat(detail.lng()).isEqualTo(105.73);
        assertThat(detail.ward()).isEqualTo("Tây Mỗ");
        assertThat(detail.province()).isEqualTo("Hà Nội");
        server.verify();
    }

    @Test
    void aPlaceIdOutsideVietnamOrMalformedIsUnknown() {
        OsmGeocoder geocoder = new OsmGeocoder(builder.build(), limiter);
        assertThat(geocoder.placeDetail("goong-id")).isEmpty();
        assertThat(geocoder.placeDetail("osm1.@@@")).isEmpty();
    }

    @Test
    void routesAreLongitudeFirstAndConverted() {
        server.expect(requestTo(containsString("/route/v1/driving/105.7,21.0;105.8,21.1"))).andRespond(withSuccess(
                """
                {"routes":[{"distance":3368.1,"duration":413.9},{"distance":4424.2,"duration":445.3}]}""", MediaType.APPLICATION_JSON));

        List<RoutePlanner.Route> routes = new OsrmRoutePlanner(builder.build(), limiter)
                .routes(new RoutePlanner.Point(21.0, 105.7), new RoutePlanner.Point(21.1, 105.8));

        assertThat(routes).containsExactly(new RoutePlanner.Route(3.4, 6.9), new RoutePlanner.Route(4.4, 7.4));
    }

    @Test
    void aTourListsTheStopsInVisitingOrder() {
        server.expect(requestTo(containsString("/trip/v1/driving/"))).andRespond(withSuccess(
                """
                {"trips":[{"distance":5817.8,"duration":641.3}],"waypoints":[{"waypoint_index":0},{"waypoint_index":2},{"waypoint_index":1}]}""",
                MediaType.APPLICATION_JSON));

        RoutePlanner.Tour tour = new OsrmRoutePlanner(builder.build(), limiter).optimize(List.of(
                new RoutePlanner.Point(21.0, 105.7), new RoutePlanner.Point(21.1, 105.8), new RoutePlanner.Point(21.2, 105.9)));

        assertThat(tour.order()).containsExactly(0, 2, 1);
        assertThat(tour.distanceKm()).isEqualTo(5.8);
    }
}
