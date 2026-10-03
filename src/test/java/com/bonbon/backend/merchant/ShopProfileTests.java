package com.bonbon.backend.merchant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An approved shop editing itself; approval is simulated in the database (the review API is tested elsewhere). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopProfileTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    Geocoder geocoder;

    @Autowired
    JdbcClient jdbc;

    String seller;
    UUID sellerId;

    @BeforeEach
    void setUp() throws Exception {
        User u = new User("profile-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        sellerId = u.getId();
        seller = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        send(put("/api/merchant/shop/steps/1"), Map.of("name", "Phở Bò", "phone", "0912345678",
                "email", "pho@example.com", "placeId", place)).andExpect(status().isOk());
        send(put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2,
                "deliveryFee", 10000, "freeDeliveryThreshold", 150000)).andExpect(status().isOk());
    }

    @Test
    void onlyAnApprovedShopEditsHere() throws Exception {
        send(patch("/api/merchant/shop"), Map.of("name", "Tên mới")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        send(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false)).andExpect(status().isConflict());
    }

    @Test
    void ordinaryFieldsApplyAtOnceAndKeepTheShopApproved() throws Exception {
        approve();
        send(patch("/api/merchant/shop"), Map.of("name", "Phở Bò Gia Truyền", "deliveryFee", 5000,
                "clearFreeDeliveryThreshold", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.shop.name").value("Phở Bò Gia Truyền"))
                .andExpect(jsonPath("$.shipping.deliveryFee").value(5000))
                .andExpect(jsonPath("$.shipping.deliveryRadiusKm").value(2))
                .andExpect(jsonPath("$.shipping.freeDeliveryThreshold").doesNotExist());

        send(patch("/api/merchant/shop"), Map.of("deliveryRadiusKm", 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_RADIUS_TOO_LARGE"));
    }

    @Test
    void aNewAddressSendsTheShopBackToReview() throws Exception {
        approve();
        String other = geocoder.autocomplete("Khu công nghiệp", null, null).get(1).placeId();
        send(patch("/api/merchant/shop"), Map.of("placeId", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.shop.address.placeId").value(other))
                .andExpect(jsonPath("$.openNow").value(false));
    }

    @Test
    void openingHoursAreReplacedWholeAndValidated() throws Exception {
        approve();
        send(put("/api/merchant/shop/opening-hours"), Map.of("openingHours",
                List.of(window(1, "06:00", "10:00"), window(1, "09:00", "11:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OPENING_HOURS_OVERLAP"));
        send(put("/api/merchant/shop/opening-hours"), Map.of("openingHours", List.of(window(6, "18:00", "02:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipping.openingHours.length()").value(1))
                .andExpect(jsonPath("$.shipping.openingHours[0].weekday").value(6));
    }

    @Test
    void pausingOverridesTheScheduleAndIsAudited() throws Exception {
        approve();
        send(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", true))
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.openNow").value(true));
        send(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.openNow").value(false));

        Map<String, Object> audit = jdbc.sql("""
                select accepting_orders_changed_by_type as type, accepting_orders_changed_by_id as id
                from vendors where owner_user_id = :o""").param("o", sellerId).query().singleRow();
        assertThat(audit.get("type")).isEqualTo("SHOP_ACCOUNT");
        assertThat(audit.get("id")).isEqualTo(sellerId);
    }

    @Test
    void customersCannotEditShops() throws Exception {
        User c = new User("cust-" + System.nanoTime() + "@example.com", null, "Khách");
        c.addRole(Role.CUSTOMER);
        c.markEmailVerified();
        String customer = tokens.issue(users.saveAndFlush(c), Role.CUSTOMER, Instant.now()).accessToken();
        mvc.perform(put("/api/merchant/shop/accepting-orders").header("Authorization", "Bearer " + customer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"accepting\":false}")).andExpect(status().isForbidden());
    }

    // --- helpers

    private void approve() {
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o")
                .param("o", sellerId).update();
    }

    /** Every day 00:00–12:00 and 12:00–00:00 (past midnight): open around the clock. */
    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(window(day, "00:00", "12:00"));
            windows.add(window(day, "12:00", "00:00"));
        }
        return windows;
    }

    private static Map<String, Object> window(int weekday, String opens, String closes) {
        return Map.of("weekday", weekday, "opensAt", opens, "closesAt", closes);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + seller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));
    }
}
