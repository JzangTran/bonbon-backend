package com.bonbon.backend.notification;

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
import com.bonbon.backend.notification.gateway.FakePushGateway;
import com.bonbon.backend.notification.gateway.PushGateway;
import com.bonbon.backend.order.service.OrderTimers;
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

/** Notifications raised by order changes, the in-app list, acknowledgement and push device registration. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NotificationTests {

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
    PushGateway push;

    @Autowired
    OrderTimers timers;

    String seller;
    UUID sellerId;
    UUID vendorId;
    String dishId;
    String customer;
    UUID customerId;
    String addressId;
    String sellerToken;
    String customerToken;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Thông Báo", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
        String suffix = Long.toString(System.nanoTime(), 36);
        sellerToken = "ExponentPushToken[seller" + suffix + "]";
        customerToken = "ExponentPushToken[customer" + suffix + "]";
    }

    @Test
    void aNewOrderNotifiesTheShopAndEachStepNotifiesTheCustomer() throws Exception {
        registerDevice(seller, sellerToken);
        registerDevice(customer, customerToken);
        String id = place();

        call(seller, get("/api/notifications"), null).andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].type").value("ORDER_NEW")).andExpect(jsonPath("$.items[0].orderId").value(id))
                .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.startsWith("Đơn mới #")));
        // The customer placed it themselves: nothing for them yet.
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.total").value(0));

        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED"));

        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].type").value("ORDER_DELIVERED")).andExpect(jsonPath("$.items[3].type").value("ORDER_CONFIRMED"));
        // The shop answering the new order settled its alert; the shop is not told about its own steps.
        call(seller, get("/api/notifications"), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void theOtherSideIsToldWhenSomeoneCancelsButNotTheOneWhoCancelled() throws Exception {
        String byCustomer = place();
        call(customer, post("/api/orders/" + byCustomer + "/cancel"), Map.of("reason", "Đổi ý"));
        call(seller, get("/api/notifications?unread=true"), null).andExpect(jsonPath("$.items[?(@.type=='ORDER_CANCELLED')]").isNotEmpty());
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.total").value(0));

        String byShop = place();
        call(seller, post("/api/merchant/orders/" + byShop + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + byShop + "/cancel"), Map.of("reason", "Hỏng bếp"));
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].type").value("ORDER_CANCELLED"))
                .andExpect(jsonPath("$.items[0].body").value("Quán đã huỷ đơn của bạn."));
    }

    @Test
    void theTimersTellBothSides() throws Exception {
        String id = place();
        jdbc.sql("update orders set placed_at = now() - interval '11 minutes' where id = :i::uuid").param("i", id).update();
        timers.runOnce(Instant.now());
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].type").value("ORDER_REJECTED"));
        call(seller, get("/api/notifications"), null).andExpect(jsonPath("$.items[?(@.type=='ORDER_AUTO_REJECTED')]").isNotEmpty())
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void pushIsSentAfterCommitWithIdsOnlyAndMarksTheNotificationDelivered() throws Exception {
        registerDevice(seller, sellerToken);
        int before = sentTo(sellerToken).size();
        String id = place();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> sentTo(sellerToken).size() > before);
        PushGateway.PushMessage message = sentTo(sellerToken).get(sentTo(sellerToken).size() - 1);
        assertThat(message.title()).startsWith("Đơn mới #");
        assertThat(message.data()).containsEntry("orderId", id).containsEntry("type", "ORDER_NEW").containsKey("notificationId");
        assertThat(message.body()).doesNotContain("0987654321"); // no phone, address or items on the lock screen
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("select delivered_at is not null from notifications where order_id = :o::uuid and audience = 'SHOP'")
                .param("o", id).query(Boolean.class).single());
    }

    @Test
    void aDeadTokenIsRetiredAndLogoutRevokesADevice() throws Exception {
        String dead = "ExponentPushToken[Dead" + Long.toString(System.nanoTime(), 36) + "]";
        registerDevice(seller, dead);
        place();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> deviceStatus(dead).equals("INVALID"));

        registerDevice(customer, customerToken);
        call(customer, post("/api/push-devices/revoke"), Map.of("token", customerToken)).andExpect(status().isNoContent());
        assertThat(deviceStatus(customerToken)).isEqualTo("REVOKED");
        int before = sentTo(customerToken).size();
        String id = place();
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        Thread.sleep(500);
        assertThat(sentTo(customerToken)).hasSize(before);
    }

    @Test
    void aTokenMovesToTheLatestUserAndOthersCannotRevokeIt() throws Exception {
        registerDevice(customer, customerToken);
        registerDevice(seller, customerToken); // same phone, another account logs in
        assertThat(jdbc.sql("select user_id from push_devices where token = :t").param("t", customerToken).query(UUID.class).single()).isEqualTo(sellerId);
        call(customer, post("/api/push-devices/revoke"), Map.of("token", customerToken)).andExpect(status().isNoContent());
        assertThat(deviceStatus(customerToken)).isEqualTo("ACTIVE"); // not the previous owner's to revoke
        call(customer, post("/api/push-devices"), Map.of("token", "not-a-token", "platform", "ANDROID")).andExpect(status().isBadRequest());
        call(customer, post("/api/push-devices"), Map.of("token", customerToken, "platform", "TOASTER")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/push-devices").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void acknowledgingIsIdempotentAndScopedToTheOwner() throws Exception {
        place();
        String notificationId = JsonPath.read(call(seller, get("/api/notifications"), null).andReturn().getResponse().getContentAsString(), "$.items[0].id");
        call(customer, post("/api/notifications/" + notificationId + "/ack"), null).andExpect(status().isNoContent());
        call(seller, get("/api/notifications?unread=true"), null).andExpect(jsonPath("$.unread").value(1)); // the customer cannot ack the shop's

        call(seller, post("/api/notifications/" + notificationId + "/ack"), null).andExpect(status().isNoContent());
        call(seller, post("/api/notifications/" + notificationId + "/ack"), null).andExpect(status().isNoContent());
        call(seller, get("/api/notifications?unread=true"), null).andExpect(jsonPath("$.unread").value(0)).andExpect(jsonPath("$.items.length()").value(0));
        call(seller, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].acknowledgedAt").exists());
        call(seller, post("/api/notifications/" + UUID.randomUUID() + "/ack"), null).andExpect(status().isNotFound());

        place();
        place();
        call(seller, post("/api/notifications/ack-all"), null).andExpect(status().isNoContent());
        call(seller, get("/api/notifications"), null).andExpect(jsonPath("$.unread").value(0));
        call(seller, get("/api/notifications?size=500"), null).andExpect(status().isBadRequest());
    }

    // --- helpers

    private String deviceStatus(String token) {
        return jdbc.sql("select status from push_devices where token = :t").param("t", token).query(String.class).single();
    }

    private List<PushGateway.PushMessage> sentTo(String token) {
        return ((FakePushGateway) push).sent().stream().filter(m -> m.token().equals(token)).toList();
    }

    private void registerDevice(String bearer, String token) throws Exception {
        call(bearer, post("/api/push-devices"), Map.of("token", token, "platform", "ANDROID", "appVersion", "1.0.0")).andExpect(status().isNoContent());
    }

    private String place() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        // Keep clear of the open-order cap by finishing nothing here: each test places at most three orders in a row.
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-notify-" + System.nanoTime()).andExpect(status().isCreated())
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
