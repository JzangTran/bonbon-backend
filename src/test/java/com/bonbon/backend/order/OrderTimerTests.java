package com.bonbon.backend.order;

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
import com.bonbon.backend.order.service.OrderTimers;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The order timers: orders are backdated in the database, then the timers run as of now. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderTimerTests {

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

    @Autowired
    OrderTimers timers;

    String seller;
    UUID vendorId;
    String dishId;
    String customer;
    String addressId;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Hẹn Giờ", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000,
                "stockQuantity", 10)).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        UUID customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void anUnansweredOrderIsRejectedByTheSystemAndTheStockComesBack() throws Exception {
        String stale = place();
        String fresh = place();
        String confirmed = place();
        call(seller, post("/api/merchant/orders/" + confirmed + "/confirm"), null);
        backdate(stale, "placed_at", "11 minutes");
        backdate(confirmed, "placed_at", "30 minutes");
        assertThat(stock()).isEqualTo(7);

        OrderTimers.Result result = timers.runOnce(Instant.now());

        assertThat(result.rejected()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(stale)).isEqualTo("REJECTED");
        assertThat(statusOf(fresh)).isEqualTo("PLACED");
        assertThat(statusOf(confirmed)).isEqualTo("CONFIRMED");
        assertThat(stock()).isEqualTo(8);
        Map<String, Object> step = jdbc.sql("select acted_by_type, acted_by_id, reason, from_status from order_status_history where order_id = :i::uuid and to_status = 'REJECTED'")
                .param("i", stale).query().singleRow();
        assertThat(step.get("acted_by_type")).isEqualTo("SYSTEM");
        assertThat(step.get("acted_by_id")).isNull();
        assertThat(step.get("reason")).isEqualTo("Quán không phản hồi kịp thời.");
        assertThat(step.get("from_status")).isEqualTo("PLACED");

        // Running again changes nothing.
        assertThat(timers.runOnce(Instant.now()).rejected()).isZero();
    }

    @Test
    void anOrderNotHandedOverWithinNinetyMinutesOfConfirmationIsCancelled() throws Exception {
        String slow = place();
        call(seller, post("/api/merchant/orders/" + slow + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + slow + "/status"), Map.of("to", "PREPARING"));
        String quick = place();
        call(seller, post("/api/merchant/orders/" + quick + "/confirm"), null);
        String gone = place();
        call(seller, post("/api/merchant/orders/" + gone + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + gone + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + gone + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        backdate(slow, "confirmed_at", "91 minutes");
        backdate(gone, "confirmed_at", "120 minutes");
        assertThat(stock()).isEqualTo(7);

        timers.runOnce(Instant.now());

        assertThat(statusOf(slow)).isEqualTo("CANCELLED");
        assertThat(statusOf(quick)).isEqualTo("CONFIRMED");
        assertThat(statusOf(gone)).isEqualTo("OUT_FOR_DELIVERY"); // already left the kitchen
        assertThat(stock()).isEqualTo(8);
        assertThat(jdbc.sql("select reason from order_status_history where order_id = :i::uuid and to_status = 'CANCELLED'").param("i", slow)
                .query(String.class).single()).isEqualTo("Quán chưa giao món trong thời hạn cho phép.");
    }

    @Test
    void anOrderOutForDeliveryForThreeHoursBecomesDeliveredUnlessACaseHoldsIt() throws Exception {
        String quiet = outForDelivery();
        String held = outForDelivery();
        String recent = outForDelivery();
        backdate(quiet, "out_for_delivery_at", "181 minutes");
        backdate(held, "out_for_delivery_at", "200 minutes");
        jdbc.sql("update orders set incident_hold = true where id = :i::uuid").param("i", held).update();
        backdate(recent, "out_for_delivery_at", "60 minutes");

        timers.runOnce(Instant.now());

        assertThat(statusOf(quiet)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("select payment_status from orders where id = :i::uuid").param("i", quiet).query(String.class).single()).isEqualTo("PAID");
        assertThat(statusOf(held)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(statusOf(recent)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(jdbc.sql("select acted_by_type from order_status_history where order_id = :i::uuid and to_status = 'DELIVERED'").param("i", quiet)
                .query(String.class).single()).isEqualTo("SYSTEM");
    }

    // --- helpers

    private String outForDelivery() throws Exception {
        String id = place();
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        return id;
    }

    private void backdate(String orderId, String column, String interval) {
        jdbc.sql("update orders set " + column + " = now() - interval '" + interval + "' where id = :i::uuid").param("i", orderId).update();
    }

    private String statusOf(String orderId) {
        return jdbc.sql("select status from orders where id = :i::uuid").param("i", orderId).query(String.class).single();
    }

    private int stock() {
        return jdbc.sql("select stock_quantity from menu_items where id = :i::uuid").param("i", dishId).query(Integer.class).single();
    }

    private String place() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-timer-" + System.nanoTime()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
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
