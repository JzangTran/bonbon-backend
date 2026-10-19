package com.bonbon.backend.platformstatistics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import com.jayway.jsonpath.JsonPath;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Platform-wide statistics: they add up, tie out with a shop's own report, fill empty periods and keep out everyone but administrators (backend#128). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PlatformStatisticsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

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

    String admin;
    String seller;
    String shopName;
    UUID vendorId;
    String dishId;
    String customer;
    String addressId;
    String place;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = LocalDate.now(VIETNAM);
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        shopName = "Quán Thống Kê " + Long.toString(System.nanoTime(), 36);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1(shopName, "0912345678", "q@example.com"));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        addressId = addressFor(customer, lat, lng);
    }

    @Test
    void theOverviewAddsUpAndTiesOutWithTheShopsOwnReport() throws Exception {
        long[] before = overviewTotals();
        String first = deliver(placeOrder(1));
        String second = deliver(placeOrder(2));
        long[] after = overviewTotals();

        long revenue = jdbc.sql("select sum(grand_total) from orders where id in (:a::uuid, :b::uuid)").param("a", first).param("b", second).query(Long.class).single();
        long commission = jdbc.sql("select sum(commission_amount) from orders where id in (:a::uuid, :b::uuid)").param("a", first).param("b", second).query(Long.class).single();
        assertThat(after[0] - before[0]).isEqualTo(2);
        assertThat(after[1] - before[1]).isEqualTo(revenue);
        assertThat(after[2] - before[2]).isEqualTo(commission);
        assertThat(after[3]).isGreaterThanOrEqualTo(before[3] + 0);

        // The shop's own report for the same range says the same, from the other side.
        call(seller, get("/api/merchant/stats/revenue").param("from", today.toString()).param("to", today.toString()), null).andExpect(jsonPath("$.totals.orders").value(2))
                .andExpect(jsonPath("$.totals.revenue").value(revenue));
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("from", today.toString()).param("to", today.toString()).param("q", shopName), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.items[0].orders").value(2)).andExpect(jsonPath("$.items[0].grossRevenue").value(revenue)).andExpect(jsonPath("$.items[0].commission").value(commission));
    }

    @Test
    void everyPeriodOfTheRangeComesBackEmptyOnesAsZero() throws Exception {
        deliver(placeOrder(1));
        call(admin, get("/api/admin/stats/overview").param("from", today.minusDays(3).toString()).param("to", today.toString()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("day")).andExpect(jsonPath("$.buckets.length()").value(4))
                .andExpect(jsonPath("$.buckets[0].start").value(today.minusDays(3).toString())).andExpect(jsonPath("$.buckets[0].orders").value(0))
                .andExpect(jsonPath("$.buckets[3].start").value(today.toString())).andExpect(jsonPath("$.buckets[3].orders").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // A week or month starts on its first day (Monday, or the 1st) even when the range starts later.
        String week = call(admin, get("/api/admin/stats/overview").param("from", today.toString()).param("to", today.toString()).param("granularity", "week"), null)
                .andExpect(jsonPath("$.buckets.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(LocalDate.parse(JsonPath.read(week, "$.buckets[0].start")).getDayOfWeek().getValue()).isEqualTo(1);
        String month = call(admin, get("/api/admin/stats/overview").param("from", today.toString()).param("to", today.toString()).param("granularity", "month"), null)
                .andExpect(jsonPath("$.buckets.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(LocalDate.parse(JsonPath.read(month, "$.buckets[0].start")).getDayOfMonth()).isEqualTo(1);
        // Defaults: the last 30 days.
        call(admin, get("/api/admin/stats/overview"), null).andExpect(jsonPath("$.buckets.length()").value(30));
    }

    @Test
    void aShopWithoutDeliveredOrdersInTheRangeIsNotListedAndOrdersNotDeliveredDoNotCount() throws Exception {
        placeOrder(1);
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("q", shopName), null).andExpect(jsonPath("$.total").value(0));
        deliver(placeOrder(1));
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("q", shopName.toLowerCase().replace("ố", "o").replace("á", "a").replace("ê", "e")), null)
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].orders").value(1));
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("from", today.minusDays(10).toString()).param("to", today.minusDays(5).toString()).param("q", shopName), null)
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void newAccountsAreCountedPerRoleOnTheDayTheRoleWasAdded() throws Exception {
        long[] before = growth();
        newUser("customer", Role.CUSTOMER);
        newUser("customer", Role.CUSTOMER);
        newUser("seller", Role.SELLER);
        UUID both = newUser("both", Role.CUSTOMER);
        User u = users.findById(both).orElseThrow();
        u.addRole(Role.SELLER);
        users.saveAndFlush(u);
        long[] after = growth();
        assertThat(after[0] - before[0]).isEqualTo(3);
        assertThat(after[1] - before[1]).isEqualTo(2);
        // An administrator is neither.
        newUser("admin", Role.ADMIN);
        long[] again = growth();
        assertThat(again[0]).isEqualTo(after[0]);
        assertThat(again[1]).isEqualTo(after[1]);
    }

    @Test
    void invalidInputIsRefusedAndOnlyAdministratorsCanRead() throws Exception {
        call(admin, get("/api/admin/stats/overview").param("granularity", "year"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANULARITY"));
        call(admin, get("/api/admin/stats/overview").param("from", today.toString()).param("to", today.minusDays(1).toString()), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        call(admin, get("/api/admin/stats/overview").param("from", today.minusYears(3).toString()).param("to", today.toString()), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("sort", "luck"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
        call(admin, get("/api/admin/stats/revenue-by-merchant").param("size", "500"), null).andExpect(status().isBadRequest());
        call(admin, get("/api/admin/stats/user-growth").param("granularity", "year"), null).andExpect(status().isBadRequest());
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/stats/overview"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/stats/revenue-by-merchant"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/stats/user-growth"), null).andExpect(status().isForbidden());
        }
    }

    // --- helpers

    /** orders, gross revenue, commission, active shops of today. */
    private long[] overviewTotals() throws Exception {
        String body = call(admin, get("/api/admin/stats/overview").param("from", today.toString()).param("to", today.toString()), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new long[] { ((Number) JsonPath.read(body, "$.totals.orders")).longValue(), ((Number) JsonPath.read(body, "$.totals.grossRevenue")).longValue(),
                ((Number) JsonPath.read(body, "$.totals.commission")).longValue(), ((Number) JsonPath.read(body, "$.totals.activeShops")).longValue() };
    }

    private long[] growth() throws Exception {
        String body = call(admin, get("/api/admin/stats/user-growth").param("from", today.toString()).param("to", today.toString()), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new long[] { ((Number) JsonPath.read(body, "$.customers")).longValue(), ((Number) JsonPath.read(body, "$.sellers")).longValue() };
    }

    private String placeOrder(int quantity) throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-pstats-" + System.nanoTime()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
        return id;
    }

    private String addressFor(String token, double lat, double lng) throws Exception {
        String id = JsonPath.read(call(token, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", id).update();
        return id;
    }

    private UUID newUser(String kind, Role role) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", null, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(Map.of("weekday", day, "opensAt", "00:00", "closesAt", "12:00"));
            windows.add(Map.of("weekday", day, "opensAt", "12:00", "closesAt", "00:00"));
        }
        return windows;
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(token, request, body, null);
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body, String idempotencyKey) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
