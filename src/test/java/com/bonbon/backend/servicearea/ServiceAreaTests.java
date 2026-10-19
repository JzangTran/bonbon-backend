package com.bonbon.backend.servicearea;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.ShopAddress;
import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The areas administrators curate, and how they place a shop and limit its delivery radius. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ServiceAreaTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    String admin;
    String seller;

    @BeforeEach
    void setUp() {
        admin = token(Role.ADMIN);
        seller = token(Role.SELLER);
    }

    @Test
    void onlyAdministratorsCurateAreas() throws Exception {
        mvc.perform(auth(get("/api/admin/service-areas"), seller)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/admin/service-areas"), seller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(area("Khu " + System.nanoTime(), 10.7769, 106.7009, 2, 1.5)))).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/admin/service-areas"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Khu thử nghiệm Hà Nội')].maxDeliveryRadiusKm").value(3.0));
    }

    @Test
    void anAreaIsValidatedAndItsNameIsUnique() throws Exception {
        String name = "Khu " + System.nanoTime();
        create(name, 10.0300, 105.7800, 2, 1.5).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));
        create(name.toUpperCase(), 10.0310, 105.7810, 2, 1.5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_AREA_NAME_TAKEN"));
        create("X", 10.7769, 106.7009, 2, 1.5).andExpect(status().isBadRequest());
        create("Khu ngoài nước " + System.nanoTime(), 48.85, 2.35, 2, 1.5).andExpect(status().isBadRequest());
        create("Khu quá rộng " + System.nanoTime(), 10.7769, 106.7009, 80, 1.5).andExpect(status().isBadRequest());
        create("Khu giao quá xa " + System.nanoTime(), 10.7769, 106.7009, 2, 0).andExpect(status().isBadRequest());
    }

    @Test
    void aShopIsPlacedInTheAreaThatContainsItsPin() throws Exception {
        step1(ShopAddress.LAT, ShopAddress.LNG).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceArea.name").value("Khu thử nghiệm Hà Nội"))
                .andExpect(jsonPath("$.maxDeliveryRadiusKm").value(3.0));
    }

    @Test
    void aPinOutsideEveryAreaIsRefused() throws Exception {
        step1(16.0544, 108.2022).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("location"));
    }

    @Test
    void whenAreasOverlapTheNearestCentreWins() throws Exception {
        String near = "Khu gần " + System.nanoTime();
        create("Khu rộng " + System.nanoTime(), 20.8449, 106.6881, 5, 3).andExpect(status().isCreated());
        create(near, 20.8450, 106.6882, 1, 2).andExpect(status().isCreated());
        step1(20.8451, 106.6883).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceArea.name").value(near))
                .andExpect(jsonPath("$.maxDeliveryRadiusKm").value(2.0));
    }

    @Test
    void theDeliveryRadiusIsLimitedByTheShopsArea() throws Exception {
        String name = "Khu nhỏ " + System.nanoTime();
        String id = JsonPath.read(create(name, 21.0064, 107.2925, 1, 1.5).andReturn().getResponse().getContentAsString(), "$.id");
        step1(21.0065, 107.2926).andExpect(status().isOk());
        step2(2).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DELIVERY_RADIUS_TOO_LARGE"))
                .andExpect(jsonPath("$.maxDeliveryRadiusKm").value(1.5));
        step2(1.5).andExpect(status().isOk());

        // The platform-wide limit still caps an area that allows more.
        mvc.perform(auth(patch("/api/admin/service-areas/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(area(name, 21.0064, 107.2925, 1, 20)))).andExpect(status().isOk());
        step2(4).andExpect(status().isBadRequest()).andExpect(jsonPath("$.maxDeliveryRadiusKm").value(3.0));
    }

    @Test
    void aSwitchedOffAreaTakesNoNewShops() throws Exception {
        String name = "Khu tắt " + System.nanoTime();
        String id = JsonPath.read(create(name, 16.4637, 107.5909, 2, 1.5).andReturn().getResponse().getContentAsString(), "$.id");
        step1(16.4637, 107.5909).andExpect(status().isOk()).andExpect(jsonPath("$.serviceArea.name").value(name));

        Map<String, Object> off = area(name, 16.4637, 107.5909, 2, 1.5);
        off.put("active", false);
        mvc.perform(auth(patch("/api/admin/service-areas/" + id), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(off))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        String other = token(Role.SELLER);
        mvc.perform(auth(put("/api/merchant/shop/steps/1"), other).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(point(16.4637, 107.5909)))).andExpect(status().isBadRequest());
        // the shop already placed in it keeps it
        mvc.perform(auth(get("/api/merchant/shop"), seller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceArea.name").value(name));
    }

    @Test
    void anUnknownAreaIsNotFound() throws Exception {
        mvc.perform(auth(patch("/api/admin/service-areas/" + UUID.randomUUID()), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(area("Không có " + System.nanoTime(), 10.7769, 106.7009, 2, 1.5))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SERVICE_AREA_NOT_FOUND"));
    }

    // --- helpers

    private ResultActions step1(double lat, double lng) throws Exception {
        return mvc.perform(auth(put("/api/merchant/shop/steps/1"), seller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(point(lat, lng))));
    }

    private Map<String, Object> point(double lat, double lng) {
        Map<String, Object> body = ShopAddress.step1("Quán khu vực", "0912345678", "khuvuc@example.com");
        body.put("lat", lat);
        body.put("lng", lng);
        return body;
    }

    private ResultActions step2(Number radius) throws Exception {
        Map<String, Object> window = Map.of("weekday", 1, "opensAt", "06:00", "closesAt", "10:00");
        return mvc.perform(auth(put("/api/merchant/shop/steps/2"), seller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("openingHours", List.of(window), "deliveryRadiusKm", radius, "deliveryFee", 10000))));
    }

    private ResultActions create(String name, double lat, double lng, double radiusKm, double maxDelivery) throws Exception {
        return mvc.perform(auth(post("/api/admin/service-areas"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(area(name, lat, lng, radiusKm, maxDelivery))));
    }

    private static Map<String, Object> area(String name, double lat, double lng, double radiusKm, double maxDelivery) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("centerLat", lat);
        m.put("centerLng", lng);
        m.put("radiusKm", radiusKm);
        m.put("maxDeliveryRadiusKm", maxDelivery);
        return m;
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(Role role) {
        User u = new User(role.name().toLowerCase() + "-" + System.nanoTime() + "@example.com", null, "Người dùng");
        u.addRole(role);
        u.markEmailVerified();
        return tokens.issue(users.saveAndFlush(u), role, Instant.now()).accessToken();
    }
}
