package com.bonbon.backend.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Live order updates over a real socket; orders are changed through MockMvc in the same application. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "bonbon.realtime.auth-timeout=PT2S")
class OrderSocketTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

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
    UUID vendorId;
    String dishId;
    String customer;
    String addressId;
    String place;
    final List<WebSocketSession> open = new ArrayList<>();

    /** Collects what a socket receives and how it closed. */
    static class Probe extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        volatile CloseStatus closed;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(JSON.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = status;
        }

        JsonNode next(long seconds) throws InterruptedException {
            return messages.poll(seconds, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Trực Tuyến", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        addressId = addressFor(customer, lat, lng);
    }

    @AfterEach
    void closeSockets() throws Exception {
        for (WebSocketSession s : open) {
            if (s.isOpen()) {
                s.close();
            }
        }
    }

    @Test
    void theShopHearsAboutANewOrderAndTheCustomerAboutEachStep() throws Exception {
        Probe shopProbe = connect(seller);
        Probe customerProbe = connect(customer);
        assertThat(shopProbe.next(5).path("type").asString()).isEqualTo("ready");
        assertThat(customerProbe.next(5).path("type").asString()).isEqualTo("ready");

        String id = place();
        JsonNode placed = shopProbe.next(5);
        assertThat(placed.path("type").asString()).isEqualTo("order");
        assertThat(placed.path("channel").asString()).isEqualTo("shop");
        assertThat(placed.path("orderId").asString()).isEqualTo(id);
        assertThat(placed.path("to").asString()).isEqualTo("PLACED");
        assertThat(placed.path("from").isNull()).isTrue();
        assertThat(customerProbe.next(5).path("channel").asString()).isEqualTo("customer");

        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        JsonNode confirmed = customerProbe.next(5);
        assertThat(confirmed.path("to").asString()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("from").asString()).isEqualTo("PLACED");
        assertThat(shopProbe.next(5).path("to").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    void someoneElsesOrdersAreNeverSent() throws Exception {
        String strangerToken = tokenOf(newUser("stranger", Role.CUSTOMER), Role.CUSTOMER);
        UUID otherSeller = newUser("seller2", Role.SELLER);
        String otherSellerToken = tokenOf(otherSeller, Role.SELLER);
        call(otherSellerToken, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherSeller).update();

        Probe stranger = connect(strangerToken);
        Probe otherShop = connect(otherSellerToken);
        assertThat(stranger.next(5).path("type").asString()).isEqualTo("ready");
        assertThat(otherShop.next(5).path("type").asString()).isEqualTo("ready");

        place();

        assertThat(stranger.next(1)).isNull();
        assertThat(otherShop.next(1)).isNull();
    }

    @Test
    void aBadTokenIsRefusedAndClosed() throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = open(probe);
        session.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"not-a-token\"}"));
        JsonNode reply = probe.next(5);
        assertThat(reply.path("type").asString()).isEqualTo("error");
        assertThat(reply.path("code").asString()).isEqualTo("UNAUTHENTICATED");
        awaitClosed(probe);
    }

    @Test
    void aSocketThatNeverAuthenticatesIsClosed() throws Exception {
        Probe probe = new Probe();
        open(probe);
        awaitClosed(probe);
        assertThat(probe.closed.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void anAdminHasNoOrderChannel() throws Exception {
        UUID adminId = newUser("admin", Role.ADMIN);
        Probe probe = connect(tokenOf(adminId, Role.ADMIN));
        JsonNode reply = probe.next(5);
        assertThat(reply.path("code").asString()).isEqualTo("FORBIDDEN");
        awaitClosed(probe);
    }

    @Test
    void pingGetsPong() throws Exception {
        Probe probe = connect(customer);
        probe.next(5);
        open.get(0).sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        assertThat(probe.next(5).path("type").asString()).isEqualTo("pong");
    }

    // --- helpers

    private WebSocketSession open(Probe probe) throws Exception {
        WebSocketSession session = new StandardWebSocketClient().execute(probe, "ws://localhost:" + port + "/ws/orders").get(5, TimeUnit.SECONDS);
        open.add(session);
        return session;
    }

    private Probe connect(String token) throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = open(probe);
        session.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + token + "\"}"));
        return probe;
    }

    private void awaitClosed(Probe probe) throws InterruptedException {
        for (int i = 0; i < 100 && probe.closed == null; i++) {
            Thread.sleep(50);
        }
        assertThat(probe.closed).isNotNull();
    }

    private String place() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-socket-" + System.nanoTime()).andExpect(status().isCreated())
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
