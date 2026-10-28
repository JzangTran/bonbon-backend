package com.bonbon.backend.common;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Object-level authorization (OWASP API1): every customer and seller endpoint that takes an id is called by another
 * customer or another shop with a valid token and a valid body, and must answer 403 or 404 without changing anything.
 * Shop A, its seller and customer A own everything; seller B (with an approved shop of their own) and customer B
 * are the intruders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ObjectLevelAuthorizationTests {

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

    String leaf;
    String place;
    double lat;

    String sellerA;
    String sellerB;
    String customerA;
    String customerB;
    UUID vendorA;
    String sectionA;
    String dishA;
    String groupA;
    String optionA;
    String addressA;
    String deliveredOrder;
    String deliveredLine;
    String waitingOrder;
    String reviewA;
    String caseA;
    String ticketA;
    String conversationA;
    String deviceA;

    @BeforeEach
    void setUp() throws Exception {
        leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        lat = -70 + (System.nanoTime() % 1000) * 0.01;

        UUID sellerAId = newUser("seller-a", Role.SELLER);
        sellerA = tokenOf(sellerAId, Role.SELLER);
        vendorA = approvedShop(sellerA, sellerAId, "Quán A");
        sectionA = JsonPath.read(body(call(sellerA, post("/api/merchant/menu-sections"), Map.of("name", "Món chính"))), "$.sections[0].id");
        dishA = JsonPath.read(body(call(sellerA, post("/api/merchant/menu-items"), Map.of("sectionId", sectionA, "categoryId", leaf,
                "name", "Cơm gà", "price", 40000))), "$.sections[0].items[0].id");
        call(sellerA, post("/api/merchant/option-groups"), groupBody());
        String groups = body(call(sellerA, get("/api/merchant/option-groups"), null));
        groupA = JsonPath.read(groups, "$.groups[0].id");
        optionA = JsonPath.read(groups, "$.groups[0].options[0].id");

        UUID sellerBId = newUser("seller-b", Role.SELLER);
        sellerB = tokenOf(sellerBId, Role.SELLER);
        approvedShop(sellerB, sellerBId, "Quán B");

        UUID customerAId = newUser("customer-a", Role.CUSTOMER);
        customerA = tokenOf(customerAId, Role.CUSTOMER);
        addressA = address(customerA);
        customerB = tokenOf(newUser("customer-b", Role.CUSTOMER), Role.CUSTOMER);
        address(customerB);

        deliveredOrder = order();
        call(sellerA, post("/api/merchant/orders/" + deliveredOrder + "/confirm"), null).andExpect(status().isOk());
        call(sellerA, post("/api/merchant/orders/" + deliveredOrder + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(sellerA, post("/api/merchant/orders/" + deliveredOrder + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(customerA, post("/api/orders/" + deliveredOrder + "/received"), null).andExpect(status().isOk());
        deliveredLine = JsonPath.read(body(call(customerA, get("/api/orders/" + deliveredOrder), null)), "$.items[0].id");
        call(customerA, post("/api/orders/" + deliveredOrder + "/review"), Map.of("rating", 4, "comment", "Ngon")).andExpect(status().is2xxSuccessful());
        reviewA = JsonPath.read(body(call(sellerA, get("/api/merchant/reviews"), null)), "$.items[0].id");
        call(customerA, post("/api/orders/" + deliveredOrder + "/incident"), Map.of("type", "MISSING_ITEM",
                "lines", List.of(Map.of("orderItemId", deliveredLine, "quantity", 1)))).andExpect(status().is2xxSuccessful());
        caseA = JsonPath.read(body(call(customerA, get("/api/orders/" + deliveredOrder + "/case"), null)), "$.id");

        waitingOrder = order();

        ticketA = JsonPath.read(body(call(customerA, post("/api/support/tickets"), Map.of("subject", "Hỏi về đơn", "message", "Đơn của tôi"))), "$.id");
        call(customerA, post("/api/shops/" + vendorA + "/messages"), Map.of("text", "Quán ơi")).andExpect(status().is2xxSuccessful());
        conversationA = JsonPath.read(body(call(customerA, get("/api/shops/" + vendorA + "/conversation"), null)), "$.id");
        call(customerA, post("/api/push-devices"), Map.of("token", "ExponentPushToken[olaa-" + System.nanoTime() + "]", "platform", "ANDROID"))
                .andExpect(status().is2xxSuccessful());
        deviceA = JsonPath.read(body(call(customerA, get("/api/push-devices"), null)), "$.items[0].id");
    }

    @Test
    void theOwnersReachTheirOwnResources() throws Exception {
        call(customerA, get("/api/orders/" + deliveredOrder), null).andExpect(status().isOk());
        call(customerA, get("/api/support/tickets/" + ticketA), null).andExpect(status().isOk());
        call(customerA, get("/api/conversations/" + conversationA + "/messages"), null).andExpect(status().isOk());
        call(sellerA, get("/api/merchant/orders/" + waitingOrder), null).andExpect(status().isOk());
        call(sellerA, get("/api/merchant/order-cases/" + caseA), null).andExpect(status().isOk());
    }

    @Test
    void anotherCustomerIsDeniedEverything() throws Exception {
        Map<String, Object> refund = Map.of("bankName", "Vietcombank", "accountNumber", "0011001234567", "accountName", "KHACH B");
        List<Probe> probes = List.of(
                probe(patch("/api/account/addresses/" + addressA), Map.of("label", "Của B")),
                probe(patch("/api/account/addresses/" + addressA + "/default"), null),
                probe(delete("/api/account/addresses/" + addressA), null),
                probe(get("/api/orders/" + deliveredOrder), null),
                probe(post("/api/orders/" + waitingOrder + "/cancel"), Map.of("reason", "B huỷ")),
                probe(get("/api/orders/" + deliveredOrder + "/case"), null),
                probe(post("/api/orders/" + deliveredOrder + "/incident/quote"), Map.of("lines", List.of(Map.of("orderItemId", deliveredLine, "quantity", 1)))),
                probe(post("/api/orders/" + deliveredOrder + "/incident"), Map.of("type", "MISSING_ITEM",
                        "lines", List.of(Map.of("orderItemId", deliveredLine, "quantity", 1)))),
                probe(get("/api/orders/" + deliveredOrder + "/no-show"), null),
                probe(post("/api/orders/" + deliveredOrder + "/no-show-answer"), Map.of("answer", "RECEIVED")),
                probe(post("/api/orders/" + waitingOrder + "/pay"), null),
                probe(post("/api/orders/" + waitingOrder + "/received"), null),
                probe(post("/api/orders/" + waitingOrder + "/report-not-received"), Map.of("note", "B")),
                probe(put("/api/orders/" + deliveredOrder + "/refund-destination"), refund),
                probe(get("/api/orders/" + deliveredOrder + "/review"), null),
                probe(put("/api/orders/" + deliveredOrder + "/review"), Map.of("rating", 1, "comment", "B sửa")),
                probe(post("/api/orders/" + deliveredOrder + "/review"), Map.of("rating", 1)),
                probe(delete("/api/orders/" + deliveredOrder + "/review"), null),
                probe(get("/api/support/tickets/" + ticketA), null),
                probe(post("/api/support/tickets/" + ticketA + "/messages"), Map.of("body", "B chen vào")),
                probe(post("/api/support/tickets/" + ticketA + "/close"), null),
                probe(get("/api/conversations/" + conversationA + "/messages"), null),
                probe(post("/api/conversations/" + conversationA + "/messages"), Map.of("text", "B chen vào")),
                probe(post("/api/conversations/" + conversationA + "/read"), null),
                probe(delete("/api/push-devices/" + deviceA), null));

        assertAllDenied(customerB, probes);
        assertUnchanged();
    }

    @Test
    void anotherShopIsDeniedEverything() throws Exception {
        List<Probe> probes = List.of(
                probe(patch("/api/merchant/menu-items/" + dishA), Map.of("name", "Món của B")),
                probe(patch("/api/merchant/menu-items/" + dishA + "/status"), Map.of("status", "SOLD_OUT")),
                probe(put("/api/merchant/menu-items/" + dishA + "/option-groups"), Map.of("groupIds", List.of())),
                probe(delete("/api/merchant/menu-items/" + dishA + "/photo"), null),
                probe(delete("/api/merchant/menu-items/" + dishA), null),
                probe(patch("/api/merchant/menu-sections/" + sectionA), Map.of("name", "Của B")),
                probe(put("/api/merchant/menu-sections/" + sectionA + "/items/order"), Map.of("ids", List.of(dishA))),
                probe(delete("/api/merchant/menu-sections/" + sectionA), null),
                probe(put("/api/merchant/option-groups/" + groupA), groupBody()),
                probe(delete("/api/merchant/option-groups/" + groupA), null),
                probe(patch("/api/merchant/options/" + optionA + "/status"), Map.of("status", "SOLD_OUT")),
                probe(get("/api/merchant/orders/" + waitingOrder), null),
                probe(post("/api/merchant/orders/" + waitingOrder + "/confirm"), null),
                probe(post("/api/merchant/orders/" + waitingOrder + "/reject"), Map.of("reason", "B từ chối")),
                probe(post("/api/merchant/orders/" + waitingOrder + "/cancel"), Map.of("reason", "B huỷ")),
                probe(post("/api/merchant/orders/" + waitingOrder + "/status"), Map.of("to", "PREPARING")),
                probe(post("/api/merchant/orders/" + deliveredOrder + "/no-show"), Map.of("note", "B")),
                probe(post("/api/merchant/reviews/" + reviewA + "/response"), Map.of("text", "B trả lời")),
                probe(put("/api/merchant/reviews/" + reviewA + "/response"), Map.of("text", "B sửa")),
                probe(delete("/api/merchant/reviews/" + reviewA + "/response"), null),
                probe(get("/api/merchant/order-cases/" + caseA), null),
                probe(post("/api/merchant/order-cases/" + caseA + "/accept"), null),
                probe(post("/api/merchant/order-cases/" + caseA + "/dispute"), Map.of("note", "B phản đối")),
                probe(get("/api/conversations/" + conversationA + "/messages"), null),
                probe(post("/api/conversations/" + conversationA + "/messages"), Map.of("text", "B chen vào")));

        assertAllDenied(sellerB, probes);
        assertUnchanged();
    }

    // ------------------------------------------------------------------ helpers

    private record Probe(MockHttpServletRequestBuilder request, Object body, String label) {
    }

    private static Probe probe(MockHttpServletRequestBuilder request, Object body) {
        var built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
        return new Probe(request, body, built.getMethod() + " " + built.getRequestURI());
    }

    /** Runs every probe and reports all the ones that were not 403/404 at once. */
    private void assertAllDenied(String token, List<Probe> probes) throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Probe p : probes) {
            int code = call(token, p.request(), p.body()).andReturn().getResponse().getStatus();
            if (code != 403 && code != 404) {
                leaks.add(p.label() + " -> " + code);
            }
        }
        assertThat(leaks).as("requests that were not denied").isEmpty();
    }

    private void assertUnchanged() {
        assertThat(jdbc.sql("select status from orders where id = :i::uuid").param("i", waitingOrder).query(String.class).single()).isEqualTo("PLACED");
        assertThat(jdbc.sql("select count(*) from addresses where id = :i::uuid").param("i", addressA).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select name from menu_items where id = :i::uuid").param("i", dishA).query(String.class).single()).isEqualTo("Cơm gà");
        assertThat(jdbc.sql("select rating from reviews where id = :i::uuid").param("i", reviewA).query(Integer.class).single()).isEqualTo(4);
    }

    private UUID approvedShop(String token, UUID ownerId, String name) throws Exception {
        call(token, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1(name, "0912345678", "q@example.com"))
                .andExpect(status().isOk());
        call(token, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000))
                .andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = 151.2 where owner_user_id = :o")
                .param("la", lat).param("o", ownerId).update();
        return jdbc.sql("select id from vendors where owner_user_id = :o").param("o", ownerId).query(UUID.class).single();
    }

    private String address(String token) throws Exception {
        String id = JsonPath.read(body(call(token, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place,
                "recipientName", "Khách Thử", "recipientPhone", "0987654321", "makeDefault", true))), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = 151.2 where id = :i::uuid").param("la", lat + 0.003).param("i", id).update();
        return id;
    }

    private String order() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorA.toString(), "addressId", addressA, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishA, "quantity", 1)));
        MockHttpServletRequestBuilder request = post("/api/orders").header("Idempotency-Key", "olaa-" + System.nanoTime());
        return JsonPath.read(body(call(customerA, request, body).andExpect(status().isCreated())), "$.id");
    }

    private static Map<String, Object> groupBody() {
        return Map.of("name", "Khẩu phần", "min", 1, "max", 1, "options", List.of(
                Map.of("name", "Thường", "priceDelta", 0, "defaultChoice", true), Map.of("name", "Lớn", "priceDelta", 10000)));
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

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
