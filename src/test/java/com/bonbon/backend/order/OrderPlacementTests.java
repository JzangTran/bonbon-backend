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

/**
 * Placing and reading orders. Each test builds its own approved shop at made-up coordinates and its own customer,
 * so the shared database does not leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderPlacementTests {

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
    double lat;
    final double lng = 151.2;

    String seller;
    UUID sellerId;
    UUID vendorId;
    String dishId;
    String sizeGroupId;
    String sizeM;
    String sizeL;

    String customer;
    UUID customerId;
    String addressId;

    @BeforeEach
    void setUp() throws Exception {
        leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        lat = -80 + (System.nanoTime() % 1000) * 0.01;

        sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Test", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000,
                "freeDeliveryThreshold", 100000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln, min_order_value = 20000 where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();

        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Trà sữa", "price", 30000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        String groups = call(seller, post("/api/merchant/option-groups"), Map.of("name", "Size", "min", 1, "max", 1,
                "options", List.of(Map.of("name", "M", "priceDelta", 0), Map.of("name", "L", "priceDelta", 5000)))).andReturn().getResponse().getContentAsString();
        sizeGroupId = JsonPath.read(groups, "$.groups[0].id");
        sizeM = JsonPath.read(groups, "$.groups[0].options[0].id");
        sizeL = JsonPath.read(groups, "$.groups[0].options[1].id");
        call(seller, put("/api/merchant/menu-items/" + dishId + "/option-groups"), Map.of("groupIds", List.of(sizeGroupId)));

        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách Thử",
                "recipientPhone", "0987654321", "detail", "Toà S2, tầng 5", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void aCodOrderIsPricedFromTheServerAndSnapshotted() throws Exception {
        call(customer, post("/api/orders"), order(List.of(line(dishId, 2, List.of(sizeL), "ít đá")), "gọi trước khi tới"), "key-snapshot-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.paymentMethod").value("COD"))
                .andExpect(jsonPath("$.shop.name").value("Quán Test"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(35000))
                .andExpect(jsonPath("$.items[0].lineTotal").value(70000))
                .andExpect(jsonPath("$.items[0].note").value("ít đá"))
                .andExpect(jsonPath("$.items[0].options[0].group").value("Size"))
                .andExpect(jsonPath("$.items[0].options[0].name").value("L"))
                .andExpect(jsonPath("$.totals.itemsTotal").value(70000))
                .andExpect(jsonPath("$.totals.deliveryFee").value(10000))
                .andExpect(jsonPath("$.totals.grandTotal").value(80000))
                .andExpect(jsonPath("$.delivery.name").value("Khách Thử"))
                .andExpect(jsonPath("$.delivery.address").value(org.hamcrest.Matchers.startsWith("Toà S2, tầng 5")))
                .andExpect(jsonPath("$.delivery.note").value("gọi trước khi tới"))
                .andExpect(jsonPath("$.timeline[0].to").value("PLACED"))
                .andExpect(jsonPath("$.timeline[0].by").value("CUSTOMER"));

        // Renaming the dish and moving the price afterwards changes nothing in the order.
        jdbc.sql("update menu_items set name = 'Tên mới', price = 99000 where id = :i::uuid").param("i", dishId).update();
        String id = jdbc.sql("select id from orders where customer_id = :c").param("c", customerId).query(UUID.class).single().toString();
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.items[0].name").value("Trà sữa"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(35000));
        Integer commission = jdbc.sql("select commission_amount from orders where id = :i::uuid").param("i", id).query(Integer.class).single();
        assertThat(commission).isEqualTo(7000); // 10 % default rate on the 70.000 food value, delivery fee excluded
    }

    @Test
    void theSameIdempotencyKeyReturnsTheSameOrder() throws Exception {
        Object body = order(List.of(line(dishId, 1, List.of(sizeM), null)), null);
        String first = call(customer, post("/api/orders"), body, "key-idem-0001").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = call(customer, post("/api/orders"), body, "key-idem-0001").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(jdbc.sql("select count(*) from orders where customer_id = :c").param("c", customerId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void freeDeliveryApplies_whenTheThresholdIsReached() throws Exception {
        call(customer, post("/api/orders"), order(List.of(line(dishId, 4, List.of(sizeM), null)), null), "key-free-0001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totals.itemsTotal").value(120000))
                .andExpect(jsonPath("$.totals.deliveryFee").value(0))
                .andExpect(jsonPath("$.totals.grandTotal").value(120000));
    }

    @Test
    void stockIsTakenAtomicallyAndAShortfallRejectsTheOrder() throws Exception {
        call(seller, patch("/api/merchant/menu-items/" + dishId), Map.of("stockQuantity", 3));
        call(customer, post("/api/orders"), order(List.of(line(dishId, 2, List.of(sizeM), null)), null), "key-stock-001").andExpect(status().isCreated());
        assertThat(stockOf(dishId)).isEqualTo(1);
        call(customer, post("/api/orders"), order(List.of(line(dishId, 2, List.of(sizeM), null)), null), "key-stock-002")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK")).andExpect(jsonPath("$.menuItemId").value(dishId));
        assertThat(stockOf(dishId)).isEqualTo(1);
    }

    @Test
    void optionRulesAreEnforced() throws Exception {
        // The required Size group is empty.
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(), null)), null), "key-opt-00001")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_COUNT_INVALID"));
        // Two choices in a group of max 1.
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM, sizeL), null)), null), "key-opt-00002")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_COUNT_INVALID"));
        // The same option twice.
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM, sizeM), null)), null), "key-opt-00003")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_DUPLICATED"));
        // An option that is not on this dish.
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(UUID.randomUUID().toString()), null)), null), "key-opt-00004")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_NOT_OFFERED"));
        // A sold-out option.
        call(seller, patch("/api/merchant/options/" + sizeL + "/status"), Map.of("status", "SOLD_OUT"));
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeL), null)), null), "key-opt-00005")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OPTION_UNAVAILABLE"));
    }

    @Test
    void shopAndDishRulesAreEnforced() throws Exception {
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-rule-00001").andExpect(status().isCreated());

        // Below the shop's minimum (20.000): one dish of 30.000 passes, so lower the price instead.
        call(seller, patch("/api/merchant/menu-items/" + dishId), Map.of("price", 5000));
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-rule-00002")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BELOW_MIN_ORDER"));
        call(seller, patch("/api/merchant/menu-items/" + dishId), Map.of("price", 30000));

        // The dish is switched off.
        call(seller, patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "SOLD_OUT"));
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-rule-00003")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
        call(seller, patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "AVAILABLE"));

        // The shop paused order intake.
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false));
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-rule-00004")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_CLOSED"));
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", true));

        // The address is outside the shop's radius.
        jdbc.sql("update addresses set lat = :la where id = :i::uuid").param("la", lat + 0.05).param("i", addressId).update();
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-rule-00005")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUT_OF_DELIVERY_RADIUS"));
    }

    @Test
    void aShopOwnerCannotOrderFromTheirOwnShop() throws Exception {
        UUID dual = sellerId;
        jdbc.sql("insert into user_roles (user_id, role) values (:u, 'CUSTOMER') on conflict do nothing").param("u", dual).update();
        String asCustomer = tokenOf(dual, Role.CUSTOMER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        String ownAddress = JsonPath.read(call(asCustomer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Chủ",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat).param("ln", lng).param("i", ownAddress).update();
        call(asCustomer, post("/api/orders"), Map.of("vendorId", vendorId.toString(), "addressId", ownAddress, "paymentMethod", "COD",
                "items", List.of(line(dishId, 1, List.of(sizeM), null))), "key-own-00001")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_ORDER_OWN_SHOP"));
    }

    @Test
    void aCustomerCannotHaveMoreThanTheOpenOrderCap() throws Exception {
        for (int i = 1; i <= 3; i++) {
            call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-cap-0000" + i).andExpect(status().isCreated());
        }
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeM), null)), null), "key-cap-00004")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_OPEN_ORDERS"));
    }

    @Test
    void ordersAreListedNewestFirstWithPreviewsAndAreVisibleOnlyToTheirCustomer() throws Exception {
        call(customer, post("/api/orders"), order(List.of(line(dishId, 2, List.of(sizeM), null)), null), "key-list-0001").andExpect(status().isCreated());
        call(customer, post("/api/orders"), order(List.of(line(dishId, 1, List.of(sizeL), null)), null), "key-list-0002").andExpect(status().isCreated());

        call(customer, get("/api/orders"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].grandTotal").value(45000))
                .andExpect(jsonPath("$.items[1].itemCount").value(2))
                .andExpect(jsonPath("$.items[1].itemsPreview").value("2× Trà sữa"))
                .andExpect(jsonPath("$.items[0].shopName").value("Quán Test"));
        call(customer, get("/api/orders?status=DELIVERED"), null).andExpect(jsonPath("$.total").value(0));
        call(customer, get("/api/orders?status=PLACED&status=CONFIRMED&size=1"), null).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2));
        call(customer, get("/api/orders?size=500"), null).andExpect(status().isBadRequest());

        String id = jdbc.sql("select id from orders where customer_id = :c limit 1").param("c", customerId).query(UUID.class).single().toString();
        UUID otherId = newUser("other", Role.CUSTOMER);
        mvc.perform(get("/api/orders/" + id).header("Authorization", "Bearer " + tokenOf(otherId, Role.CUSTOMER))).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/" + id).header("Authorization", "Bearer " + seller)).andExpect(status().isForbidden());
    }

    @Test
    void theRequestIsValidated() throws Exception {
        call(customer, post("/api/orders"), order(List.of(), null), "key-valid-001").andExpect(status().isBadRequest());
        call(customer, post("/api/orders"), order(List.of(line(dishId, 0, List.of(sizeM), null)), null), "key-valid-002").andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + customer).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(order(List.of(line(dishId, 1, List.of(sizeM), null)), null)))).andExpect(status().isBadRequest());
        call(customer, post("/api/orders"), Map.of("vendorId", UUID.randomUUID().toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(line(dishId, 1, List.of(sizeM), null))), "key-valid-003").andExpect(status().isNotFound());
        call(customer, post("/api/orders"), Map.of("vendorId", vendorId.toString(), "addressId", UUID.randomUUID().toString(), "paymentMethod", "COD",
                "items", List.of(line(dishId, 1, List.of(sizeM), null))), "key-valid-004").andExpect(status().isNotFound());
    }

    // --- helpers

    private Integer stockOf(String id) {
        return jdbc.sql("select stock_quantity from menu_items where id = :i::uuid").param("i", id).query(Integer.class).single();
    }

    private Map<String, Object> order(List<Map<String, Object>> items, String note) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", items));
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private static Map<String, Object> line(String dishId, int quantity, List<String> optionIds, String note) {
        Map<String, Object> line = new java.util.HashMap<>(Map.of("menuItemId", dishId, "quantity", quantity, "optionIds", optionIds));
        if (note != null) {
            line.put("note", note);
        }
        return line;
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
