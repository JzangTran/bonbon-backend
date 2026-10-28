package com.bonbon.backend.identity.account;

import java.time.Instant;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.account.service.AddressSuggestionService;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.geo.PlaceSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests run with the fake geocoder (no Goong key in the test profile). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AddressSuggestionTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    Geocoder geocoder;

    String access;

    @BeforeEach
    void setUp() {
        User u = new User("geo-" + System.nanoTime() + "@example.com", null, "Khách");
        u.addRole(Role.CUSTOMER);
        u.markEmailVerified();
        access = tokens.issue(users.saveAndFlush(u), Role.CUSTOMER, Instant.now()).accessToken();
    }

    @Test
    void suggestionsNeedASignedInUser() throws Exception {
        mvc.perform(get("/api/geo/autocomplete").param("input", "toà s2")).andExpect(status().isUnauthorized());
    }

    @Test
    void shortInputGivesNoSuggestionsAndCostsNothing() throws Exception {
        mvc.perform(get("/api/geo/autocomplete").param("input", "to").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void suggestionsCarryPlaceIdsThatResolveToCoordinates() throws Exception {
        mvc.perform(get("/api/geo/autocomplete").param("input", "toà s2").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].placeId").isNotEmpty())
                .andExpect(jsonPath("$[0].province").value("Hà Nội"));

        PlaceSuggestion picked = geocoder.autocomplete("toà s2", null, null).get(1);
        assertThat(geocoder.placeDetail(picked.placeId())).hasValueSatisfying(d -> {
            assertThat(d.formattedAddress()).startsWith("toà s2 (gợi ý 2)");
            assertThat(d.lat()).isBetween(21.0, 21.1);
        });
        assertThat(geocoder.placeDetail("not-a-place")).isEmpty();
    }

    @Test
    void perUserLimitAnswersTooManyRequests() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/geo/autocomplete").param("input", "đường " + i).header("Authorization", "Bearer " + access))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/geo/autocomplete").param("input", "đường 31").header("Authorization", "Bearer " + access))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void overlongInputIsRejected() throws Exception {
        mvc.perform(get("/api/geo/autocomplete").param("input", "a".repeat(201)).header("Authorization", "Bearer " + access))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theProvincesAreTheThirtyFourOfTheMergerAndNeedASignedInUser() throws Exception {
        mvc.perform(get("/api/geo/provinces")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/geo/provinces").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(34))
                .andExpect(jsonPath("$[?(@.name == 'Thành phố Hà Nội')].code").value(1));
    }

    @Test
    void aProvinceListsItsWardsAndAnUnknownOneIs404() throws Exception {
        mvc.perform(get("/api/geo/provinces/1/wards").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(126))
                .andExpect(jsonPath("$[?(@.name == 'Phường Hoàn Kiếm')].code").value(70));
        mvc.perform(get("/api/geo/provinces/999/wards").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVINCE_NOT_FOUND"));
    }

    @Test
    void thePinOnTheMapBecomesAnAddressMatchedToTheUnits() throws Exception {
        mvc.perform(get("/api/geo/reverse").param("lat", "21.0285").param("lng", "105.8542").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lat").value(21.0285))
                .andExpect(jsonPath("$.provinceCode").value(1))
                .andExpect(jsonPath("$.wardCode").value(70))
                .andExpect(jsonPath("$.street").value(org.hamcrest.Matchers.startsWith("Vị trí 21.02850")));
    }

    @Test
    void aPinOutsideVietnamIsRejected() throws Exception {
        mvc.perform(get("/api/geo/reverse").param("lat", "51.5").param("lng", "-0.12").header("Authorization", "Bearer " + access))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/geo/reverse").param("lat", "abc").param("lng", "105").header("Authorization", "Bearer " + access))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPickedSuggestionBecomesAPoint() throws Exception {
        String placeId = geocoder.autocomplete("toà s2", null, null).get(0).placeId();
        mvc.perform(get("/api/geo/place").param("placeId", placeId).header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lat").isNumber())
                .andExpect(jsonPath("$.provinceCode").value(1));
        mvc.perform(get("/api/geo/place").param("placeId", "fake:nope").header("Authorization", "Bearer " + access))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    void theStreetIsWhatIsLeftOfTheFullAddressAfterTheWardAndProvince() {
        assertThat(AddressSuggestionService.streetOf("Số 1 Ngõ 2, Tây Mỗ, Hà Nội", "Tây Mỗ", "Hà Nội")).isEqualTo("Số 1 Ngõ 2");
        assertThat(AddressSuggestionService.streetOf("Tây Mỗ, Hà Nội", "Phường Tây Mỗ", "Thành phố Hà Nội")).isNull();
        assertThat(AddressSuggestionService.streetOf("Quận Hoàn Kiếm, Hà Nội", "Hoàn Kiếm", "Hà Nội")).isNull();
        assertThat(AddressSuggestionService.streetOf("12 Phố Hàng Bài, Hoàn Kiếm, Quận Hoàn Kiếm, Hà Nội", "Hoàn Kiếm", "Hà Nội")).isEqualTo("12 Phố Hàng Bài");
        assertThat(AddressSuggestionService.streetOf(null, "x", "y")).isNull();
    }
}
