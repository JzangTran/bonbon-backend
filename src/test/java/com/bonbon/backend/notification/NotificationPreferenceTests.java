package com.bonbon.backend.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.notification.gateway.FakePushGateway;
import com.bonbon.backend.notification.gateway.PushGateway;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What users choose to receive: the catalog, locked categories, quiet hours, devices, and that push really follows the choices (backend#118). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, NotificationPreferenceTests.Clocks.class })
class NotificationPreferenceTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

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
    MovableClock clock;

    @MockitoBean
    EmailSender emailSender;

    String seller;
    UUID sellerId;
    UUID vendorId;
    String dishId;
    String customer;
    UUID customerId;
    String addressId;
    String place;
    String sellerDevice;
    String customerDevice;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.now());
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Tuỳ Chọn", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = addressFor(customer, lat, lng);
        sellerDevice = "ExponentPushToken[seller" + Long.toString(System.nanoTime(), 36) + "]";
        customerDevice = "ExponentPushToken[customer" + Long.toString(System.nanoTime(), 36) + "]";
        call(seller, post("/api/push-devices"), Map.of("token", sellerDevice, "platform", "ANDROID", "appVersion", "1.0.0")).andExpect(status().isNoContent());
        call(customer, post("/api/push-devices"), Map.of("token", customerDevice, "platform", "IOS", "appVersion", "1.0.0")).andExpect(status().isNoContent());
    }

    @Test
    void everyoneStartsWithTheDefaultsOfTheirRole() throws Exception {
        call(customer, get("/api/me/notification-preferences"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.category=='ORDER_PROGRESS')].channels[0].enabled").value(true))
                .andExpect(jsonPath("$.categories[?(@.category=='CHAT_MESSAGES')].channels[?(@.channel=='EMAIL')].enabled").value(false))
                .andExpect(jsonPath("$.categories[?(@.category=='SHOP_NOTICES')]").isEmpty())
                .andExpect(jsonPath("$.categories[?(@.category=='ORDER_ALERTS')].locked").value(true))
                .andExpect(jsonPath("$.quietHours").doesNotExist());
        call(seller, get("/api/me/notification-preferences"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[?(@.category=='SHOP_NOTICES')]").isNotEmpty())
                .andExpect(jsonPath("$.categories[?(@.category=='ORDER_PROGRESS')]").isEmpty());
        String admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        call(admin, get("/api/me/notification-preferences"), null).andExpect(status().isForbidden());
    }

    @Test
    void aChoiceIsKeptAndGoingBackToTheDefaultDeletesIt() throws Exception {
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_PROGRESS", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories[?(@.category=='ORDER_PROGRESS')].channels[0].enabled").value(false))
                .andExpect(jsonPath("$.categories[?(@.category=='ORDER_PROGRESS')].channels[0].defaultEnabled").value(true));
        assertThat(rows(customerId)).isEqualTo(1);
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_PROGRESS", "channel", "PUSH", "enabled", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories[?(@.category=='ORDER_PROGRESS')].channels[0].enabled").value(true));
        assertThat(rows(customerId)).isZero();
    }

    @Test
    void aLockedCategoryCanNeverBeChangedAndNothingIsHalfApplied() throws Exception {
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_ALERTS", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CATEGORY_LOCKED"));
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ACCOUNT_SECURITY", "channel", "EMAIL", "enabled", false))))
                .andExpect(status().isUnprocessableContent());
        // One bad entry rejects the whole request.
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(
                Map.of("category", "ORDER_PROGRESS", "channel", "PUSH", "enabled", false), Map.of("category", "ORDER_ALERTS", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isUnprocessableContent());
        assertThat(rows(customerId)).isZero();

        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "NOPE", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "SHOP_NOTICES", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_CATEGORY"));
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_PROGRESS", "channel", "EMAIL", "enabled", true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_AVAILABLE"));
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of())).andExpect(status().isBadRequest());
    }

    @Test
    void quietHoursAreValidatedAndCanBeCleared() throws Exception {
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "22:00", "end", "06:30")).andExpect(status().isOk())
                .andExpect(jsonPath("$.quietHours.start").value("22:00")).andExpect(jsonPath("$.quietHours.end").value("06:30"))
                .andExpect(jsonPath("$.quietHours.timeZone").value("Asia/Ho_Chi_Minh"));
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "22:00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUIET_HOURS_INCOMPLETE"));
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "22:00", "end", "22:00")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUIET_HOURS_EMPTY"));
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "25:00", "end", "06:00")).andExpect(status().isBadRequest());
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "22:00", "end", "06:00", "timeZone", "Mars/Base")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TIME_ZONE_INVALID"));
        call(customer, put("/api/me/quiet-hours"), Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.quietHours").doesNotExist());
    }

    @Test
    void aSilencedProgressPushIsNotSentButTheNewOrderAlertAlwaysIs() throws Exception {
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_PROGRESS", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isOk());
        String id = placeOrder();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(sellerDevice).isEmpty());
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        Thread.sleep(700);
        assertThat(sentTo(customerDevice)).isEmpty();
        // The in-app list is unaffected.
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].type").value("ORDER_CONFIRMED"));

        // Turned back on, the next step does reach the phone.
        call(customer, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "ORDER_PROGRESS", "channel", "PUSH", "enabled", true))))
                .andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(customerDevice).isEmpty());
    }

    @Test
    void quietHoursHoldBackAProgressPushButNotAnOrderAlert() throws Exception {
        // 23:00 in Vietnam, inside 22:00 - 06:00 (a window that crosses midnight).
        clock.set(Instant.parse("2026-10-10T16:00:00Z"));
        call(customer, put("/api/me/quiet-hours"), Map.of("start", "22:00", "end", "06:00")).andExpect(status().isOk());
        call(seller, put("/api/me/quiet-hours"), Map.of("start", "22:00", "end", "06:00")).andExpect(status().isOk());
        String id = placeOrder();
        // Orders and their alerts ignore quiet hours.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(sellerDevice).isEmpty());
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        Thread.sleep(700);
        assertThat(sentTo(customerDevice)).isEmpty();
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].type").value("ORDER_CONFIRMED"));

        // At 12:00 the window is over.
        clock.set(Instant.parse("2026-10-10T05:00:00Z"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(customerDevice).isEmpty());
    }

    @Test
    void aChatMessageNudgesTheOfflineSideOncePerMinuteWithoutItsText() throws Exception {
        call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", "Nội dung bí mật 12345")).andExpect(status().isCreated());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(sellerDevice).isEmpty());
        PushGateway.PushMessage message = sentTo(sellerDevice).get(0);
        assertThat(message.title()).isEqualTo("Tin nhắn mới");
        assertThat(message.body()).doesNotContain("bí mật").doesNotContain("12345");
        assertThat(message.data()).containsEntry("type", "CHAT_MESSAGE").containsKey("conversationId");

        // A burst is one nudge.
        call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", "Alo?")).andExpect(status().isCreated());
        Thread.sleep(700);
        assertThat(sentTo(sellerDevice)).hasSize(1);

        // The shop answers; the customer is nudged too.
        String conversation = jdbc.sql("select id from conversations where customer_id = :c").param("c", customerId).query(UUID.class).single().toString();
        call(seller, post("/api/conversations/" + conversation + "/messages"), Map.of("text", "Dạ có")).andExpect(status().isCreated());
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !sentTo(customerDevice).isEmpty());
        assertThat(sentTo(customerDevice).get(0).body()).contains("cửa hàng");
    }

    @Test
    void chatPushAndEmailFollowTheChoices() throws Exception {
        call(seller, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "CHAT_MESSAGES", "channel", "PUSH", "enabled", false))))
                .andExpect(status().isOk());
        call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", "Xin chào")).andExpect(status().isCreated());
        Thread.sleep(700);
        assertThat(sentTo(sellerDevice)).isEmpty();
        Mockito.verify(emailSender, Mockito.never()).send(Mockito.any());

        call(seller, put("/api/me/notification-preferences"), Map.of("changes", List.of(Map.of("category", "CHAT_MESSAGES", "channel", "EMAIL", "enabled", true))))
                .andExpect(status().isOk());
        String conversation = jdbc.sql("select id from conversations where customer_id = :c").param("c", customerId).query(UUID.class).single().toString();
        call(customer, post("/api/conversations/" + conversation + "/messages"), Map.of("text", "Còn không ạ?")).andExpect(status().isCreated());
        ArgumentCaptor<EmailSender.EmailMessage> sent = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        Mockito.verify(emailSender, Mockito.timeout(10_000)).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(jdbc.sql("select email from users where id = :id").param("id", sellerId).query(String.class).single());
        assertThat(sent.getValue().textBody()).doesNotContain("Còn không");
    }

    @Test
    void devicesAreListedAndRemovedOnlyByTheirOwner() throws Exception {
        String list = call(customer, get("/api/push-devices"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].platform").value("IOS")).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$.items[0].id");
        assertThat(list).doesNotContain(customerDevice);

        call(seller, delete("/api/push-devices/" + id), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));
        call(customer, delete("/api/push-devices/" + id), null).andExpect(status().isNoContent());
        call(customer, get("/api/push-devices"), null).andExpect(jsonPath("$.items.length()").value(0));
        call(customer, delete("/api/push-devices/" + id), null).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select status from push_devices where token = :t").param("t", customerDevice).query(String.class).single()).isEqualTo("REVOKED");
    }

    // --- helpers

    private long rows(UUID user) {
        return jdbc.sql("select count(*) from notification_preferences where user_id = :u").param("u", user).query(Long.class).single();
    }

    private List<PushGateway.PushMessage> sentTo(String token) {
        return ((FakePushGateway) push).sent().stream().filter(m -> m.token().equals(token)).toList();
    }

    private String placeOrder() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-pref-" + System.nanoTime()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
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
        return tokens.issue(users.findById(userId).orElseThrow(), role, clock.instant()).accessToken();
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
