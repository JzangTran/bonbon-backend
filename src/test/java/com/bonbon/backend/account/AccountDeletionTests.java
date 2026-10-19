package com.bonbon.backend.account;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.account.service.AccountDeletionService;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.common.mail.EmailSender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

/** Deleting my account: what blocks it, the grace period that signing in cancels, and the anonymizing that keeps orders and history (backend#133). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountDeletionTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "mat-khau-thu-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Geocoder geocoder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AccountDeletionService deletions;

    @MockitoBean
    EmailSender emailSender;

    String place;
    double lat;

    @BeforeEach
    void setUp() {
        lat = -80 + (System.nanoTime() % 1000) * 0.01;
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
    }

    @Test
    void aPersonWithNothingInTheWayCanDeleteAfterAFreshSignInAndConfirming() throws Exception {
        Person p = person("customer", Role.CUSTOMER, true);
        call(p.token, get("/api/me/account/deletion-check"), null).andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.graceDays").value(7))
                .andExpect(jsonPath("$.needsPassword").value(true)).andExpect(jsonPath("$.blockers.length()").value(0));

        String stale = token(p.id, Role.CUSTOMER, Instant.now().minus(Duration.ofMinutes(30)));
        call(stale, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "xoá đi", "password", PASSWORD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIRMATION_MISMATCH"));
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", "sai-mat-khau")).andExpect(status().isUnauthorized());
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE")).andExpect(status().isUnauthorized());

        // The device is registered, then every session and the device end the moment the person asks.
        call(p.token, post("/api/push-devices"), Map.of("token", "ExponentPushToken[del" + p.id + "]", "platform", "ANDROID", "appVersion", "1")).andExpect(status().isNoContent());
        // Tokens are stamped by the second, so one issued in the same second as the request would still be accepted.
        Thread.sleep(1100);
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", p.email.toUpperCase(), "password", PASSWORD)).andExpect(status().isOk()).andExpect(jsonPath("$.graceDays").value(7))
                .andExpect(jsonPath("$.executesAt").exists());
        assertThat(state(p.id)).isEqualTo("PENDING_DELETION");
        call(p.token, get("/api/me/account/deletion-check"), null).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("select status from push_devices where user_id = :u").param("u", p.id).query(String.class).single()).isEqualTo("REVOKED");
        ArgumentCaptor<EmailSender.EmailMessage> sent = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        Mockito.verify(emailSender, Mockito.timeout(10_000)).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(p.email);

        // Nothing is erased before the end of the grace period.
        assertThat(deletions.executeDue(Instant.now().plus(Duration.ofDays(6)))).isZero();
        assertThat(state(p.id)).isEqualTo("PENDING_DELETION");
    }

    @Test
    void signingInDuringTheGracePeriodCancelsTheDeletion() throws Exception {
        Person p = person("customer", Role.CUSTOMER, true);
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("email", p.email, "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletionCancelled").value(true)).andExpect(jsonPath("$.tokens.accessToken").exists());
        assertThat(state(p.id)).isEqualTo("ACTIVE");
        deletions.executeDue(Instant.now().plus(Duration.ofDays(30)));
        assertThat(state(p.id)).isEqualTo("ACTIVE");
        // An ordinary sign-in says nothing about a cancellation.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("email", p.email, "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deletionCancelled").doesNotExist());
    }

    @Test
    void afterTheGracePeriodTheIdentityIsAnonymizedAndHistoryStays() throws Exception {
        Person p = person("customer", Role.CUSTOMER, true);
        jdbc.sql("update users set phone = '0911222333', avatar_url = 'https://x/y.png' where id = :u").param("u", p.id).update();
        jdbc.sql("insert into user_auth_providers (user_id, provider, provider_id) values (:u, 'GOOGLE', :p)").param("u", p.id).param("p", "google-" + p.id).update();
        Shop shop = shop();
        String address = JsonPath.read(call(p.token, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách", "recipientPhone", "0987654321", "makeDefault", true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", 151.2).param("i", address).update();
        String order = deliver(shop, JsonPath.read(call(p.token, post("/api/orders"), Map.of("vendorId", shop.vendorId.toString(), "addressId", address, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", shop.dish, "quantity", 1))), "key-del-" + System.nanoTime()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
        call(p.token, post("/api/shops/" + shop.vendorId + "/messages"), Map.of("text", "Cảm ơn quán")).andExpect(status().isCreated());

        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isOk());
        assertThat(deletions.executeDue(Instant.now().plus(Duration.ofDays(8)))).isGreaterThanOrEqualTo(1);
        assertThat(deletions.executeDue(Instant.now().plus(Duration.ofDays(8)))).isZero();

        assertThat(state(p.id)).isEqualTo("DELETED");
        assertThat(jdbc.sql("select name from users where id = :u").param("u", p.id).query(String.class).single()).isEqualTo("Người dùng đã xóa");
        assertThat(jdbc.sql("select email from users where id = :u").param("u", p.id).query(String.class).single()).endsWith("@deleted.invalid").doesNotContain(p.email);
        assertThat(jdbc.sql("select phone is null and avatar_url is null and password_hash is null from users where id = :u").param("u", p.id).query(Boolean.class).single()).isTrue();
        assertThat(count("user_auth_providers", "user_id", p.id)).isZero();
        assertThat(count("addresses", "customer_id", p.id)).isZero();
        assertThat(count("push_devices", "user_id", p.id)).isZero();
        assertThat(count("refresh_tokens", "user_id", p.id)).isZero();
        // Kept: the order and what was said, under the deleted name.
        assertThat(jdbc.sql("select count(*) from orders where id = :o::uuid and customer_id = :u").param("o", order).param("u", p.id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from messages m join conversations c on c.id = m.conversation_id where c.customer_id = :u").param("u", p.id).query(Long.class).single()).isEqualTo(1);
        // The address and the Google account are free for a new identity.
        User again = new User(p.email, null, "Người mới");
        again.addRole(Role.CUSTOMER);
        assertThat(users.saveAndFlush(again).getId()).isNotEqualTo(p.id);
        // The old sign-in no longer works.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("email", p.email, "password", PASSWORD)))).andExpect(status().isUnauthorized());
        Mockito.verify(emailSender, Mockito.timeout(10_000).atLeast(2)).send(Mockito.any());
    }

    @Test
    void anAccountThatSignsInWithGoogleOnlyNeedsNoPassword() throws Exception {
        Person p = person("customer", Role.CUSTOMER, false);
        call(p.token, get("/api/me/account/deletion-check"), null).andExpect(jsonPath("$.needsPassword").value(false));
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE")).andExpect(status().isOk());
    }

    @Test
    void ordersCasesTicketsAndAnOpenShopStandInTheWay() throws Exception {
        Shop shop = shop();
        Person p = person("customer", Role.CUSTOMER, true);
        String address = JsonPath.read(call(p.token, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách", "recipientPhone", "0987654321", "makeDefault", true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", 151.2).param("i", address).update();
        String order = JsonPath.read(call(p.token, post("/api/orders"), Map.of("vendorId", shop.vendorId.toString(), "addressId", address, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", shop.dish, "quantity", 1))), "key-del-" + System.nanoTime()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(p.token, post("/api/support/tickets"), Map.of("subject", "Hỏi", "message", "x")).andExpect(status().isCreated());

        String body = call(p.token, get("/api/me/account/deletion-check"), null).andExpect(jsonPath("$.allowed").value(false)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(body, "$.blockers[*].code")).containsExactlyInAnyOrder("ORDERS_IN_PROGRESS", "OPEN_TICKETS");
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELETION_BLOCKED"))
                .andExpect(jsonPath("$.blockers.length()").value(2));
        assertThat(state(p.id)).isEqualTo("ACTIVE");

        // The shop owner: an approved shop blocks, a closed one does not.
        String sellerBody = call(shop.sellerToken, get("/api/me/account/deletion-check"), null).andExpect(jsonPath("$.allowed").value(false)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(sellerBody, "$.blockers[*].code")).contains("SHOP_NOT_CLOSED");
        deliver(shop, order);
        jdbc.sql("update vendors set status = 'CLOSED' where id = :v").param("v", shop.vendorId).update();
        call(shop.sellerToken, get("/api/me/account/deletion-check"), null).andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void administratorsDoNotDeleteThemselvesAndNothingIsDeletedTwice() throws Exception {
        String admin = token(newUser("admin", Role.ADMIN, null), Role.ADMIN, Instant.now());
        call(admin, get("/api/me/account/deletion-check"), null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_A_PERSON_ACCOUNT"));
        Person p = person("customer", Role.CUSTOMER, true);
        call(p.token, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isOk());
        String again = token(p.id, Role.CUSTOMER, Instant.now());
        call(again, post("/api/me/account/delete"), Map.of("confirmation", "DELETE", "password", PASSWORD)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_PENDING"));
    }

    // --- helpers

    private record Person(UUID id, String email, String token) {
    }

    private record Shop(UUID vendorId, String dish, String sellerToken, UUID sellerId) {
    }

    private Person person(String kind, Role role, boolean withPassword) {
        UUID id = newUser(kind, role, withPassword ? passwords.encode(PASSWORD) : null);
        String email = jdbc.sql("select email from users where id = :u").param("u", id).query(String.class).single();
        return new Person(id, email, token(id, role, Instant.now()));
    }

    private String state(UUID id) {
        return jdbc.sql("select status from users where id = :u").param("u", id).query(String.class).single();
    }

    private long count(String table, String column, UUID id) {
        return jdbc.sql("select count(*) from " + table + " where " + column + " = :u").param("u", id).query(Long.class).single();
    }

    private Shop shop() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        UUID sellerId = newUser("seller", Role.SELLER, passwords.encode(PASSWORD));
        String seller = token(sellerId, Role.SELLER, Instant.now());
        call(seller, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1("Quán Xoá TK " + System.nanoTime(), "0912345678", "q@example.com"));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", lat).param("ln", 151.2).param("o", sellerId).update();
        UUID vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        String dish = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        return new Shop(vendorId, dish, seller, sellerId);
    }

    private String deliver(Shop shop, String id) throws Exception {
        call(shop.sellerToken, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        for (String to : List.of("PREPARING", "OUT_FOR_DELIVERY", "DELIVERED")) {
            call(shop.sellerToken, post("/api/merchant/orders/" + id + "/status"), Map.of("to", to)).andExpect(status().isOk());
        }
        return id;
    }

    private UUID newUser(String kind, Role role, String passwordHash) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", passwordHash, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String token(UUID userId, Role role, Instant authTime) {
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
