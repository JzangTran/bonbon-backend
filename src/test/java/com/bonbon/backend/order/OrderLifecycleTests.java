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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The order lifecycle from both sides; each test builds its own shop, dish and customer. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderLifecycleTests {

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
    UUID vendorId;
    String dishId;
    String customer;
    UUID customerId;
    String addressId;
    int keySeq;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;

        sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Vòng Đời", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm sườn",
                "price", 40000, "stockQuantity", 10)).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");

        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách Thử",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void anOrderWalksTheWholeLifecycleAndCodIsPaidAtTheEnd() throws Exception {
        String id = place(2);
        assertThat(stock()).isEqualTo(8);

        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.handoverDeadline").exists())
                .andExpect(jsonPath("$.responseDeadline").doesNotExist());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(jsonPath("$.status").value("PREPARING"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED")).andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.timeline.length()").value(5))
                .andExpect(jsonPath("$.timeline[1].by").value("SHOP")).andExpect(jsonPath("$.timeline[4].by").value("CUSTOMER"));

        assertThat(stock()).isEqualTo(8); // delivered orders keep their stock consumed
        assertThat(jdbc.sql("select finished_at is not null and confirmed_at is not null and out_for_delivery_at is not null from orders where id = :i::uuid")
                .param("i", id).query(Boolean.class).single()).isTrue();
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.customerPhone").value("0987654321")).andExpect(jsonPath("$.contactMasked").value(false));
        // Nothing moves a finished order again.
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void theShopMayMarkDeliveredAndTheSecondPressLoses() throws Exception {
        String id = place(1);
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_ALREADY_CHANGED"));
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void stepsCannotBeSkippedAndTheSamePressTwiceIsHarmless() throws Exception {
        String id = place(1);
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isConflict());
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_ALREADY_CHANGED"));
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void rejectingNeedsAReasonAndGivesTheStockBack() throws Exception {
        String id = place(3);
        assertThat(stock()).isEqualTo(7);
        call(seller, post("/api/merchant/orders/" + id + "/reject"), Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        call(seller, post("/api/merchant/orders/" + id + "/reject"), Map.of("reason", "Hết nguyên liệu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.timeline[1].reason").value("Hết nguyên liệu"));
        assertThat(stock()).isEqualTo(10);
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.timeline[1].reason").value("Hết nguyên liệu"));
        assertThat(jdbc.sql("select payment_status from orders where id = :i::uuid").param("i", id).query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void theCustomerCancelsFreelyUntilPreparingStarts() throws Exception {
        String early = place(1);
        call(customer, post("/api/orders/" + early + "/cancel"), Map.of("reason", "Đổi ý")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.timeline[1].by").value("CUSTOMER"));
        assertThat(stock()).isEqualTo(10);

        String confirmed = place(1);
        call(seller, post("/api/merchant/orders/" + confirmed + "/confirm"), null);
        call(customer, post("/api/orders/" + confirmed + "/cancel"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        String preparing = place(1);
        call(seller, post("/api/merchant/orders/" + preparing + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + preparing + "/status"), Map.of("to", "PREPARING"));
        call(customer, post("/api/orders/" + preparing + "/cancel"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void theShopCanCancelAfterConfirmingButNeedsAReason() throws Exception {
        String id = place(2);
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + id + "/cancel"), Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        call(seller, post("/api/merchant/orders/" + id + "/cancel"), Map.of("reason", "Hỏng bếp")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock()).isEqualTo(10);
        // From the door there is no cancel.
        String out = place(1);
        call(seller, post("/api/merchant/orders/" + out + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + out + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + out + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        call(seller, post("/api/merchant/orders/" + out + "/cancel"), Map.of("reason", "x")).andExpect(status().isConflict());
    }

    @Test
    void theShopListsAndReadsOnlyItsOwnOrders() throws Exception {
        String first = place(1);
        place(1);
        call(seller, post("/api/merchant/orders/" + first + "/confirm"), null);

        call(seller, get("/api/merchant/orders?status=PLACED&sort=oldest"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].responseDeadline").exists()).andExpect(jsonPath("$.items[0].customerName").value("Khách Thử"))
                .andExpect(jsonPath("$.items[0].itemsPreview").value("1× Cơm sườn"));
        call(seller, get("/api/merchant/orders"), null).andExpect(jsonPath("$.total").value(2));
        call(seller, get("/api/merchant/orders?status=CONFIRMED&status=PLACED&size=1"), null).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(2));
        call(seller, get("/api/merchant/orders?from=2999-01-01"), null).andExpect(jsonPath("$.total").value(0));
        call(seller, get("/api/merchant/orders?size=500"), null).andExpect(status().isBadRequest());

        UUID otherSeller = newUser("seller2", Role.SELLER);
        String otherToken = tokenOf(otherSeller, Role.SELLER);
        mvc.perform(get("/api/merchant/orders/" + first).header("Authorization", "Bearer " + otherToken)).andExpect(status().isConflict());
        mvc.perform(post("/api/merchant/orders/" + first + "/confirm").header("Authorization", "Bearer " + otherToken)).andExpect(status().isConflict());
        call(customer, get("/api/merchant/orders"), null).andExpect(status().isForbidden());
        call(seller, get("/api/orders"), null).andExpect(status().isForbidden());
    }

    @Test
    void anotherShopsApprovedSellerGetsNotFound() throws Exception {
        String id = place(1);
        UUID otherSeller = newUser("seller3", Role.SELLER);
        String otherToken = tokenOf(otherSeller, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(otherToken, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherSeller).update();
        mvc.perform(get("/api/merchant/orders/" + id).header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
        mvc.perform(post("/api/merchant/orders/" + id + "/confirm").header("Authorization", "Bearer " + otherToken)).andExpect(status().isNotFound());
    }

    @Test
    void contactDetailsAreMaskedOnceTheReportWindowHasPassedUnlessACaseIsOpen() throws Exception {
        String id = place(1);
        call(seller, post("/api/merchant/orders/" + id + "/reject"), Map.of("reason", "Đóng cửa"));
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(jsonPath("$.contactMasked").value(false)).andExpect(jsonPath("$.customerPhone").value("0987654321"));

        jdbc.sql("update orders set finished_at = now() - interval '25 hours' where id = :i::uuid").param("i", id).update();
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(jsonPath("$.contactMasked").value(true))
                .andExpect(jsonPath("$.customerPhone").value("098xxxx321")).andExpect(jsonPath("$.customerName").value("Khách Thử"));

        jdbc.sql("update orders set incident_hold = true where id = :i::uuid").param("i", id).update();
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(jsonPath("$.contactMasked").value(false));
    }

    @Test
    void unpaidOrdersAreNeverShownToTheShop() throws Exception {
        String id = place(1);
        jdbc.sql("update orders set status = 'PENDING_PAYMENT' where id = :i::uuid").param("i", id).update();
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(status().isNotFound());
        call(seller, get("/api/merchant/orders"), null).andExpect(jsonPath("$.total").value(0));
    }

    // --- helpers

    private int stock() {
        return jdbc.sql("select stock_quantity from menu_items where id = :i::uuid").param("i", dishId).query(Integer.class).single();
    }

    /** Places a COD order for {@code quantity} of the dish and returns its id. */
    private String place(int quantity) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-life-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
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
