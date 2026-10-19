package com.bonbon.backend.merchantapproval;

import java.time.Duration;
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
import com.bonbon.backend.merchantapproval.service.ShopClosureService;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
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

/** A seller closes the shop: it stops taking orders, finishes what it has, and is closed once nothing is left to settle (backend#132). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShopClosureTests {

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
    ShopClosureService closures;

    String admin;
    String seller;
    UUID sellerId;
    UUID vendorId;
    String dishId;
    String customer;
    String addressId;
    String place;
    String shopName;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN, Instant.now());
        sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER, Instant.now());
        shopName = "Quán Đóng Cửa " + Long.toString(System.nanoTime(), 36);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1(shopName, "0912345678", "q@example.com"));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", lat).param("ln", 151.2).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER, Instant.now());
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách", "recipientPhone", "0987654321", "makeDefault", true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", 151.2).param("i", addressId).update();
    }

    @Test
    void theShopStopsTakingOrdersFinishesWhatItHasAndClosesWhenNothingIsLeft() throws Exception {
        String delivered = deliver(placeOrder());
        String inProgress = placeOrder();
        call(seller, post("/api/merchant/orders/" + inProgress + "/confirm"), null).andExpect(status().isOk());

        // Asking to close needs a recent sign-in and a reason.
        String stale = tokenOf(sellerId, Role.SELLER, Instant.now().minus(Duration.ofMinutes(30)));
        call(stale, post("/api/merchant/shop/close"), Map.of("reason", "STOP_SELLING")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "OTHER")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_TEXT_REQUIRED"));
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "BECAUSE")).andExpect(status().isBadRequest());
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "RELOCATING")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSING"));
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "RELOCATING")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_CANNOT_CLOSE"));

        // Gone from browsing, takes no new orders, but the seller still works the orders it has.
        call(customer, get("/api/vendors?lat=10&lng=106"), null).andExpect(status().isOk());
        assertThat(jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("CLOSING");
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        assertThat(call(customer, post("/api/orders"), body, "key-closing-" + System.nanoTime()).andReturn().getResponse().getStatus()).isBetween(400, 499);
        call(seller, get("/api/merchant/orders/" + inProgress), null).andExpect(status().isOk());

        // The owner reads the same blockers while the shop is closing.
        call(seller, get("/api/merchant/closure"), null).andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("RELOCATING"))
                .andExpect(jsonPath("$.blockers.ordersInProgress").value(1)).andExpect(jsonPath("$.blockers.ready").value(false));
        call(customer, get("/api/merchant/closure"), null).andExpect(status().isForbidden());

        // The administrators see what stands in the way, and closing by hand is refused until it is clear.
        String listed = call(admin, get("/api/admin/merchants/closing"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> mine = JsonPath.read(listed, "$.items[?(@.vendorId=='" + vendorId + "')]");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0)).containsEntry("reason", "RELOCATING");
        assertThat(((Map<?, ?>) mine.get(0).get("blockers")).get("ordersInProgress")).isEqualTo(1);
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSURE_BLOCKED"))
                .andExpect(jsonPath("$.blockers.ordersInProgress").value(1)).andExpect(jsonPath("$.blockers.ready").value(false));
        closures.finishReady();
        assertThat(jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("CLOSING");

        // The order is finished; the ledger is not at zero yet (the shop owes commission), so it still cannot close.
        for (String to : List.of("PREPARING", "OUT_FOR_DELIVERY", "DELIVERED")) {
            call(seller, post("/api/merchant/orders/" + inProgress + "/status"), Map.of("to", to)).andExpect(status().isOk());
        }
        long balance = ((Number) JsonPath.read(call(admin, get("/api/admin/settlement/vendors/" + vendorId), null).andReturn().getResponse().getContentAsString(), "$.balance")).longValue();
        assertThat(balance).isNotZero();
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.blockers.ordersInProgress").value(0))
                .andExpect(jsonPath("$.blockers.balance").value(balance));

        // An administrator clears the ledger: the platform's decision, with a reason.
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "ADJUSTMENT", "amount", -balance, "note", "Xoá khoản còn lại khi quán đóng cửa"), "close-" + System.nanoTime()).andExpect(status().isCreated());
        assertThat(closures.finishReady()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("select closed_at is not null from vendors where id = :v").param("v", vendorId).query(Boolean.class).single()).isTrue();
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict());

        // The seller was told twice, and history stays readable.
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> count("SHOP_CLOSING") == 1 && count("SHOP_CLOSED") == 1);
        call(admin, get("/api/admin/merchants/" + vendorId), null).andExpect(status().isOk()).andExpect(jsonPath("$.shop.status").value("CLOSED"));
        assertThat(delivered).isNotBlank();
    }

    @Test
    void anOpenAppealOrHeldMoneyStandsInTheWay() throws Exception {
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "STOP_SELLING")).andExpect(status().isOk());
        UUID penalty = UUID.randomUUID();
        jdbc.sql("""
                insert into shop_penalties (id, vendor_id, points, source, reason, status, issued_at, expires_at, appeal_status, appeal_reason, appealed_at)
                values (:id, :v, 1, 'MANUAL', 'thử', 'ACTIVE', now(), now() + interval '30 days', 'PENDING', 'không đúng', now())""").param("id", penalty).param("v", vendorId).update();
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.blockers.pendingAppeals").value(1));

        jdbc.sql("update shop_penalties set appeal_status = 'REJECTED' where id = :id").param("id", penalty).update();
        jdbc.sql("insert into settlement_case_holds (case_id, vendor_id, amount, created_at) values (gen_random_uuid(), :v, 5000, now())").param("v", vendorId).update();
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.blockers.heldForCases").value(5000));

        jdbc.sql("update settlement_case_holds set released_at = now() where vendor_id = :v").param("v", vendorId).update();
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    void aSuspendedShopCanCloseToo() throws Exception {
        jdbc.sql("update vendors set status = 'SUSPENDED' where id = :v").param("v", vendorId).update();
        call(seller, post("/api/merchant/shop/close"), Map.of("reason", "TOO_FEW_ORDERS")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSING"));
    }

    @Test
    void onlyTheOwnerAsksAndOnlyAdministratorsFinish() throws Exception {
        String stranger = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER, Instant.now());
        call(stranger, post("/api/merchant/shop/close"), Map.of("reason", "STOP_SELLING")).andExpect(status().isForbidden());
        UUID noShop = newUser("seller", Role.SELLER);
        call(tokenOf(noShop, Role.SELLER, Instant.now()), post("/api/merchant/shop/close"), Map.of("reason", "STOP_SELLING")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/merchants/closing"), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isForbidden());
        }
        call(admin, post("/api/admin/merchants/" + UUID.randomUUID() + "/finish-closing"), null).andExpect(status().isNotFound());
        call(admin, post("/api/admin/merchants/" + vendorId + "/finish-closing"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_CLOSING"));
    }

    // --- helpers

    private long count(String type) {
        return jdbc.sql("select count(*) from notifications where recipient_id = :u and type = :t").param("u", sellerId).param("t", type).query(Long.class).single();
    }

    private String placeOrder() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-closure-" + System.nanoTime()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
        return id;
    }

    private UUID newUser(String kind, Role role) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", null, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String tokenOf(UUID userId, Role role, Instant authTime) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, authTime).accessToken();
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
