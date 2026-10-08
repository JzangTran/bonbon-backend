package com.bonbon.backend.statistics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.order.service.OrderTimers;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Revenue over time and best-selling dishes of one shop (backend#88). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class StatisticsTests {

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
    MomoSigner signer;

    @Autowired
    MomoSettings settings;

    @Autowired
    OrderTimers timers;

    @Autowired
    SystemSettingsService systemSettings;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

    String admin;
    String seller;
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

        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Đối Soát", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm sườn",
                "price", 40000, "stockQuantity", 50)).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");

        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Trần Văn An",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void revenueCountsOnlyDeliveredOrdersAndFillsEveryDay() throws Exception {
        deliver(paidOrder(2));      // 90.000 paid online
        deliver(placeCod(1));       // 50.000 cash
        String cancelled = paidOrder(1);
        call(customer, post("/api/orders/" + cancelled + "/cancel"), null).andExpect(status().isOk());
        String open = placeCod(1);  // still waiting for the shop

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString();
        call(seller, get("/api/merchant/stats/revenue"), null).andExpect(status().isOk()).andExpect(jsonPath("$.granularity").value("day"))
                .andExpect(jsonPath("$.buckets.length()").value(7)).andExpect(jsonPath("$.totals.orders").value(2))
                .andExpect(jsonPath("$.totals.revenue").value(140_000)).andExpect(jsonPath("$.totals.averageOrderValue").value(70_000))
                .andExpect(jsonPath("$.buckets[6].start").value(today)).andExpect(jsonPath("$.buckets[6].orders").value(2))
                .andExpect(jsonPath("$.buckets[6].revenue").value(140_000)).andExpect(jsonPath("$.buckets[6].averageOrderValue").value(70_000))
                .andExpect(jsonPath("$.buckets[0].orders").value(0)).andExpect(jsonPath("$.buckets[0].revenue").value(0))
                .andExpect(jsonPath("$.buckets[0].averageOrderValue").value(0));
        call(seller, post("/api/merchant/orders/" + open + "/reject"), Map.of("reason", "Dọn")).andExpect(status().isOk());

        for (String g : List.of("week", "month")) {
            call(seller, get("/api/merchant/stats/revenue?granularity=" + g), null).andExpect(jsonPath("$.totals.revenue").value(140_000))
                    .andExpect(jsonPath("$.totals.orders").value(2));
        }
    }

    @Test
    void ordersAreBucketedByWhenTheyWereDeliveredAndTheRangeIsInclusive() throws Exception {
        String old = placeCod(1);
        deliver(old);               // 50.000
        deliver(placeCod(2));       // 90.000
        java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(3);
        jdbc.sql("update orders set finished_at = :t where id = :i::uuid").param("t", java.sql.Timestamp.from(day.atTime(23, 30).atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant()))
                .param("i", old).update();

        call(seller, get("/api/merchant/stats/revenue?from=" + day + "&to=" + day), null).andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].orders").value(1)).andExpect(jsonPath("$.buckets[0].revenue").value(50_000));
        call(seller, get("/api/merchant/stats/revenue?from=" + day.plusDays(1) + "&to=" + day.plusDays(2)), null).andExpect(jsonPath("$.totals.orders").value(0));
        call(seller, get("/api/merchant/stats/revenue?from=" + day + "&to=" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))), null)
                .andExpect(jsonPath("$.buckets.length()").value(4)).andExpect(jsonPath("$.totals.orders").value(2)).andExpect(jsonPath("$.totals.revenue").value(140_000));
        // The week and the month are named by their first day, in Vietnam time.
        String body = call(seller, get("/api/merchant/stats/revenue?from=" + day + "&to=" + day + "&granularity=week"), null).andReturn().getResponse().getContentAsString();
        java.time.LocalDate monday = java.time.LocalDate.parse(JsonPath.read(body, "$.buckets[0].start"));
        assertThat(monday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(monday).isBeforeOrEqualTo(day).isAfter(day.minusDays(7));
        body = call(seller, get("/api/merchant/stats/revenue?from=" + day + "&to=" + day + "&granularity=month"), null).andReturn().getResponse().getContentAsString();
        assertThat(java.time.LocalDate.parse(JsonPath.read(body, "$.buckets[0].start")).getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void badRangesAreRefused() throws Exception {
        call(seller, get("/api/merchant/stats/revenue?granularity=year"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANULARITY"));
        call(seller, get("/api/merchant/stats/revenue?from=2026-12-01&to=2026-01-01"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        call(seller, get("/api/merchant/stats/revenue?from=2020-01-01&to=2026-01-01"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        call(seller, get("/api/merchant/stats/revenue?from=2020-01-01&to=2026-01-01&granularity=month"), null).andExpect(status().isOk());
        call(seller, get("/api/merchant/stats/best-selling-dishes?limit=0"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        call(seller, get("/api/merchant/stats/best-selling-dishes?limit=51"), null).andExpect(status().isBadRequest());
        call(seller, get("/api/merchant/stats/best-selling-dishes?from=2026-12-01&to=2026-01-01"), null).andExpect(status().isBadRequest());
    }

    @Test
    void bestSellersAreRankedByQuantityWithTheNameTheyHadWhenOrdered() throws Exception {
        String section = JsonPath.<List<String>>read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Phụ")).andReturn().getResponse().getContentAsString(),
                "$.sections[?(@.name == 'Phụ')].id").get(0);
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        String soup = JsonPath.<List<String>>read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Canh chua",
                "price", 30000, "stockQuantity", 50)).andReturn().getResponse().getContentAsString(), "$.sections[?(@.name == 'Phụ')].items[0].id").get(0);

        deliver(placeMixed(Map.of(dishId, 2, soup, 1)));    // 2 x 40.000 rice, 1 x 30.000 soup
        deliver(placeMixed(Map.of(dishId, 1)));
        deliver(placeMixed(Map.of(soup, 1)));
        placeMixed(Map.of(soup, 5));                          // not delivered: never counted
        jdbc.sql("update menu_items set name = 'Đổi tên rồi' where id = :i::uuid").param("i", dishId).update();

        call(seller, get("/api/merchant/stats/best-selling-dishes"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].menuItemId").value(dishId)).andExpect(jsonPath("$.items[0].name").value("Cơm sườn"))
                .andExpect(jsonPath("$.items[0].quantity").value(3)).andExpect(jsonPath("$.items[0].revenue").value(120_000))
                .andExpect(jsonPath("$.items[1].name").value("Canh chua")).andExpect(jsonPath("$.items[1].quantity").value(2))
                .andExpect(jsonPath("$.items[1].revenue").value(60_000));
        call(seller, get("/api/merchant/stats/best-selling-dishes?limit=1"), null).andExpect(jsonPath("$.items.length()").value(1));
        call(seller, get("/api/merchant/stats/best-selling-dishes?from=2020-01-01&to=2020-01-02"), null).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aShopOnlySeesItsOwnNumbers() throws Exception {
        deliver(placeCod(1));
        UUID otherOwner = newUser("seller2", Role.SELLER);
        String other = tokenOf(otherOwner, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(other, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherOwner).update();
        call(other, get("/api/merchant/stats/revenue"), null).andExpect(jsonPath("$.totals.orders").value(0)).andExpect(jsonPath("$.totals.revenue").value(0));
        call(other, get("/api/merchant/stats/best-selling-dishes"), null).andExpect(jsonPath("$.items.length()").value(0));
        call(seller, get("/api/merchant/stats/revenue"), null).andExpect(jsonPath("$.totals.orders").value(1));
    }

    @Test
    void onlyAShopWithAnApprovedShopCanReadStatistics() throws Exception {
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        for (String path : List.of("/api/merchant/stats/revenue", "/api/merchant/stats/best-selling-dishes")) {
            call(fresh, get(path), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
            call(customer, get(path), null).andExpect(status().isForbidden());
            call(admin, get(path), null).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    // --- scenario helpers

    private String placeCod(int quantity) throws Exception {
        return placeMixed(Map.of(dishId, quantity));
    }

    private String placeMixed(Map<String, Integer> quantities) throws Exception {
        keySeq++;
        List<Map<String, Object>> items = new ArrayList<>();
        quantities.forEach((dish, qty) -> items.add(Map.of("menuItemId", dish, "quantity", qty)));
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", items);
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-stats-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String paidOrder(int quantity) throws Exception {
        String id = placeOnline(quantity);
        ipn(providerOrderId(id), 40000L * quantity + 10000, 0).andExpect(status().isNoContent());
        return id;
    }

    private void deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk());
    }

    // --- helpers

    private int stock() {
        return jdbc.sql("select stock_quantity from menu_items where id = :d::uuid").param("d", dishId).query(Integer.class).single();
    }

    private Instant placedAt(String orderId) {
        return jdbc.sql("select placed_at from orders where id = :o::uuid").param("o", orderId).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
    }

    private int history(String orderId) {
        return jdbc.sql("select count(*) from order_status_history where order_id = :o::uuid").param("o", orderId).query(Integer.class).single();
    }

    private int notifications(String audience) {
        return jdbc.sql("select count(*) from notifications where audience = :a and recipient_id = :r")
                .param("a", audience).param("r", audience.equals("SHOP") ? sellerUserId() : customerId).query(Integer.class).single();
    }

    private UUID sellerUserId() {
        return jdbc.sql("select owner_user_id from vendors where id = :v").param("v", vendorId).query(UUID.class).single();
    }

    private String providerOrderId(String orderId) {
        return jdbc.sql("""
                select a.provider_order_id from payment_attempts a join payments p on p.id = a.payment_id
                where p.order_id = :o::uuid order by a.attempt_no desc limit 1""").param("o", orderId).query(String.class).single();
    }

    private String placeOnline(int quantity) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "ONLINE",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-pay-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Map<String, Object> signedIpn(String providerOrderId, long amount, int resultCode) {
        return signedIpn(providerOrderId, amount, resultCode, settings.partnerCode());
    }

    /** What MoMo would post, signed with the configured keys. */
    private Map<String, Object> signedIpn(String providerOrderId, long amount, int resultCode, String partnerCode) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("amount", amount);
        fields.put("extraData", "");
        fields.put("message", resultCode == 0 ? "Successful." : "Declined");
        fields.put("orderId", providerOrderId);
        fields.put("orderInfo", "Thanh toan don");
        fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", partnerCode);
        fields.put("payType", "qr");
        fields.put("requestId", UUID.randomUUID().toString());
        fields.put("responseTime", 1_700_000_000_000L);
        fields.put("resultCode", resultCode);
        fields.put("transId", 4_000_000_001L);
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("signature", signer.sign(fields));
        return body;
    }

    private ResultActions ipn(String providerOrderId, long amount, int resultCode) throws Exception {
        return postIpn(signedIpn(providerOrderId, amount, resultCode));
    }

    private ResultActions postIpn(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/payments/momo/ipn")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
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
