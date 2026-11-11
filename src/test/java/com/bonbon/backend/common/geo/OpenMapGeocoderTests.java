package com.bonbon.backend.common.geo;

import java.util.List;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request shape and parsing against the documented OpenMap.vn Google-style payloads (docs.openmap.vn). */
class OpenMapGeocoderTests {

    RestClient.Builder builder = RestClient.builder().baseUrl("https://mapapis.openmap.vn/v1");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    RateLimiter limiter = mock(RateLimiter.class);
    OpenMapGeocoder openmap;

    @BeforeEach
    void setUp() {
        when(limiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);
        openmap = new OpenMapGeocoder(builder.build(), "test-key", limiter);
    }

    @Test
    void autocompleteSendsTheKeyAndTheNewUnitsAndReadsTheLevels() {
        server.expect(requestTo(startsWith("https://mapapis.openmap.vn/v1/autocomplete"))).andExpect(queryParam("apikey", "test-key"))
                .andExpect(queryParam("admin_v2", "true"))
                .andRespond(withSuccess("""
                        {"predictions":[{"description":"Công Ty 44+, 12 Ngõ 1 Dịch Vọng Hậu, phường Dịch Vọng Hậu, thành phố Hà Nội",
                          "place_id":"BFMh","structured_formatting":{"main_text":"Công Ty 44+","secondary_text":"12 Ngõ 1 Dịch Vọng Hậu, phường Dịch Vọng Hậu, thành phố Hà Nội"},
                          "terms":[{"offset":0,"value":"Công Ty 44+"},{"offset":13,"value":"12 Ngõ 1 Dịch Vọng Hậu"},{"offset":37,"value":"phường Dịch Vọng Hậu"},{"offset":59,"value":"thành phố Hà Nội"}]}],
                         "status":"OK"}""", MediaType.APPLICATION_JSON));

        List<PlaceSuggestion> found = openmap.autocomplete("44+", null, null);

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.placeId()).isEqualTo("BFMh");
            assertThat(s.mainText()).isEqualTo("Công Ty 44+");
            assertThat(s.ward()).isEqualTo("Dịch Vọng Hậu");
            assertThat(s.province()).isEqualTo("Hà Nội");
        });
        server.verify();
    }

    @Test
    void placeDetailAsksForTheGoogleShapeAndReadsThePoint() {
        server.expect(requestTo(startsWith("https://mapapis.openmap.vn/v1/place"))).andExpect(queryParam("ids", "aQA0"))
                .andExpect(queryParam("format", "google"))
                .andRespond(withSuccess("""
                        {"result":{"place_id":"aQA0","formatted_address":"Lotte Mall Tây Hồ, 272 Đường Võ Chí Công, phường Phú Thượng, thành phố Hà Nội",
                          "geometry":{"location":{"lat":21.075951,"lng":105.812662},"viewport":null},
                          "address_components":[{"long_name":"Lotte Mall Tây Hồ"},{"long_name":"272 Đường Võ Chí Công"},{"long_name":"phường Phú Thượng"},{"long_name":"thành phố Hà Nội"}],
                          "name":"Lotte Mall Tây Hồ"},"status":"OK"}""", MediaType.APPLICATION_JSON));

        PlaceDetail detail = openmap.placeDetail("aQA0").orElseThrow();

        assertThat(detail.lat()).isEqualTo(21.075951);
        assertThat(detail.lng()).isEqualTo(105.812662);
        assertThat(detail.ward()).isEqualTo("Phú Thượng");
        assertThat(detail.province()).isEqualTo("Hà Nội");
    }

    @Test
    void reverseKeepsThePinWhereTheUserDroppedIt() {
        server.expect(requestTo(startsWith("https://mapapis.openmap.vn/v1/geocode/reverse"))).andExpect(queryParam("latlng", "21.07,105.81"))
                .andRespond(withSuccess("""
                        {"results":[{"formatted_address":"272 Đường Võ Chí Công, phường Phú Thượng, thành phố Hà Nội","place_id":"aQA0",
                          "geometry":{"location":{"lat":21.075951,"lng":105.812662}},
                          "address_components":[{"long_name":"272 Đường Võ Chí Công"},{"long_name":"phường Phú Thượng"},{"long_name":"thành phố Hà Nội"}]}],
                         "status":"OK"}""", MediaType.APPLICATION_JSON));

        PlaceDetail detail = openmap.reverse(21.07, 105.81).orElseThrow();

        assertThat(detail.lat()).isEqualTo(21.07);
        assertThat(detail.placeId()).isEqualTo("aQA0");
        assertThat(detail.ward()).isEqualTo("Phú Thượng");
    }
    @Test
    void aPlaceEndingOnARoadHasNoProvinceAndLosesItsEmptyTail() {
        server.expect(requestTo(startsWith("https://mapapis.openmap.vn/v1/autocomplete"))).andRespond(withSuccess("""
                {"predictions":[{"description":"Vinhomes Smart City,  ","place_id":"x1","structured_formatting":{"main_text":"Vinhomes Smart City","secondary_text":""},
                  "terms":[{"value":"Vinhomes Smart City"},{"value":""}]},
                 {"description":"Vinhomes Smart City Tây Mỗ, Cao Tốc Láng - Hòa Lạc","place_id":"x2",
                  "terms":[{"value":"Vinhomes Smart City Tây Mỗ"},{"value":"Cao Tốc Láng - Hòa Lạc"}]}],"status":"OK"}""", MediaType.APPLICATION_JSON));

        List<PlaceSuggestion> found = openmap.autocomplete("vinhomes", null, null);

        assertThat(found).extracting(PlaceSuggestion::description).containsExactly("Vinhomes Smart City", "Vinhomes Smart City Tây Mỗ, Cao Tốc Láng - Hòa Lạc");
        assertThat(found).extracting(PlaceSuggestion::province).containsOnlyNulls();
    }
}
