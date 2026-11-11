package com.bonbon.backend.operations.appconfig;

import java.time.Duration;
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
import com.bonbon.backend.ordering.order.service.OrderTimers;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Minimum app version and maintenance mode: the public config, what the filter refuses and spares, and who may change it (backend#131). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AppConfigTests {

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

    String admin;
    String customer;
    UUID customerId;

    @BeforeEach
    void setUp() {
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN, Instant.now());
        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER, Instant.now());
    }

    /** The settings are shared by every test in the run, so each test puts them back. */
    @AfterEach
    void reset() throws Exception {
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "NONE", "reason", "dọn sau test"));
        change(admin, "/api/admin/app-config/version-policy/IOS", Map.of("minSupportedVersion", "0.0.0", "latestVersion", "0.0.0", "reason", "dọn sau test"));
        change(admin, "/api/admin/app-config/version-policy/ANDROID", Map.of("minSupportedVersion", "0.0.0", "latestVersion", "0.0.0", "reason", "dọn sau test"));
    }

    @Test
    void theConfigTellsEachBuildWhetherItMustUpdate() throws Exception {
        call(null, get("/api/app/config")).andExpect(status().isOk()).andExpect(jsonPath("$.platform").doesNotExist()).andExpect(jsonPath("$.updateRequired").value(false))
                .andExpect(jsonPath("$.maintenance.level").value("NONE")).andExpect(jsonPath("$.maintenance.active").value(false)).andExpect(jsonPath("$.serverTime").exists());
        change(admin, "/api/admin/app-config/version-policy/IOS", Map.of("minSupportedVersion", "1.9.0", "latestVersion", "2.0.0", "storeUrl", "https://apps.apple.com/app/bonbon",
                "message", "Hãy cập nhật để tiếp tục", "reason", "API mới")).andExpect(status().isOk());

        call(null, get("/api/app/config").header("X-App-Platform", "IOS").header("X-App-Version", "1.8.5")).andExpect(jsonPath("$.updateRequired").value(true))
                .andExpect(jsonPath("$.storeUrl").value("https://apps.apple.com/app/bonbon")).andExpect(jsonPath("$.updateMessage").value("Hãy cập nhật để tiếp tục"));
        // 1.10.0 is newer than 1.9.0 (compared part by part, not as text); older than the latest, so only a prompt.
        call(null, get("/api/app/config").header("X-App-Platform", "ios").header("X-App-Version", "1.10.0")).andExpect(jsonPath("$.updateRequired").value(false))
                .andExpect(jsonPath("$.updateAvailable").value(true));
        call(null, get("/api/app/config").header("X-App-Platform", "IOS").header("X-App-Version", "2.0.0")).andExpect(jsonPath("$.updateRequired").value(false))
                .andExpect(jsonPath("$.updateAvailable").value(false));
        // Another platform, an unreadable version, or no headers at all (web) are never locked out.
        call(null, get("/api/app/config").header("X-App-Platform", "ANDROID").header("X-App-Version", "0.0.1")).andExpect(jsonPath("$.updateRequired").value(false));
        call(null, get("/api/app/config").header("X-App-Platform", "IOS").header("X-App-Version", "dev")).andExpect(jsonPath("$.updateRequired").value(false));
        call(null, get("/api/app/config").header("X-App-Platform", "IOS")).andExpect(jsonPath("$.updateRequired").value(false));
    }

    @Test
    void anOldBuildIsRefusedEverywhereExceptWhereItCanSignInAndLearnWhy() throws Exception {
        change(admin, "/api/admin/app-config/version-policy/IOS", Map.of("minSupportedVersion", "2.0.0", "latestVersion", "2.0.0", "storeUrl", "https://apps.apple.com/app/bonbon", "reason", "API mới"))
                .andExpect(status().isOk());

        call(customer, get("/api/notifications").header("X-App-Platform", "IOS").header("X-App-Version", "1.9.9")).andExpect(status().isUpgradeRequired())
                .andExpect(jsonPath("$.code").value("APP_UPDATE_REQUIRED")).andExpect(jsonPath("$.storeUrl").value("https://apps.apple.com/app/bonbon"));
        // It does not matter that the token is missing or bad: the build is too old to be useful.
        call(null, get("/api/notifications").header("X-App-Platform", "IOS").header("X-App-Version", "1.0")).andExpect(status().isUpgradeRequired());
        call(customer, get("/api/notifications").header("X-App-Platform", "IOS").header("X-App-Version", "2.0.0")).andExpect(status().isOk());
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        call(customer, get("/api/notifications").header("X-App-Platform", "ANDROID").header("X-App-Version", "0.1")).andExpect(status().isOk());
        // Signing in (and the config) still work for the old build, so the screen can always be shown.
        call(null, post("/api/auth/login").header("X-App-Platform", "IOS").header("X-App-Version", "1.0.0").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        call(null, get("/api/app/config").header("X-App-Platform", "IOS").header("X-App-Version", "1.0.0")).andExpect(status().isOk());
    }

    @Test
    void fullMaintenanceRefusesEverythingExceptTheWayBackIn() throws Exception {
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "FULL", "message", "Nâng cấp hệ thống", "endsAt", Instant.now().plus(Duration.ofHours(1)).toString(), "reason", "nâng cấp"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maintenance.active").value(true));

        call(customer, get("/api/notifications")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MAINTENANCE"))
                .andExpect(jsonPath("$.detail").value("Nâng cấp hệ thống")).andExpect(header().exists("Retry-After"));
        call(customer, post("/api/push-devices").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\",\"platform\":\"IOS\"}")).andExpect(status().isServiceUnavailable());
        call(null, get("/api/help-articles")).andExpect(status().isServiceUnavailable());
        // The config says so, a payment provider's callback is never turned away, and administrators can still work and switch it off.
        call(null, get("/api/app/config")).andExpect(status().isOk()).andExpect(jsonPath("$.maintenance.level").value("FULL")).andExpect(jsonPath("$.maintenance.message").value("Nâng cấp hệ thống"));
        assertThat(call(null, post("/api/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isNotEqualTo(503);
        call(admin, get("/api/admin/app-config")).andExpect(status().isOk());
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "NONE", "reason", "xong")).andExpect(status().isOk());
        call(customer, get("/api/notifications")).andExpect(status().isOk());
    }

    @Test
    void readOnlyMaintenanceLetsPeopleLookButNotChangeAnything() throws Exception {
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "READ_ONLY", "reason", "di chuyển dữ liệu")).andExpect(status().isOk());
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        call(customer, post("/api/push-devices").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"t\",\"platform\":\"IOS\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MAINTENANCE_READ_ONLY"));
        call(null, post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void maintenanceOnlyCountsInsideItsWindow() throws Exception {
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "FULL", "startsAt", Instant.now().plus(Duration.ofHours(2)).toString(),
                "endsAt", Instant.now().plus(Duration.ofHours(3)).toString(), "reason", "báo trước")).andExpect(status().isOk()).andExpect(jsonPath("$.maintenance.active").value(false));
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        call(null, get("/api/app/config")).andExpect(jsonPath("$.maintenance.level").value("FULL")).andExpect(jsonPath("$.maintenance.active").value(false)).andExpect(jsonPath("$.maintenance.startsAt").exists());
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "FULL", "startsAt", Instant.now().minus(Duration.ofHours(3)).toString(),
                "endsAt", Instant.now().minus(Duration.ofHours(1)).toString(), "reason", "đã qua")).andExpect(status().isOk());
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "FULL", "startsAt", Instant.now().plus(Duration.ofHours(3)).toString(),
                "endsAt", Instant.now().plus(Duration.ofHours(1)).toString(), "reason", "sai")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MAINTENANCE_WINDOW_INVALID"));
    }

    @Test
    void aShopIsNotBlamedForOrdersTheSystemClosedDuringMaintenance() throws Exception {
        Setup s = shop();
        String during = s.place();
        String after = s.place();
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "FULL", "reason", "bảo trì")).andExpect(status().isOk());
        jdbc.sql("update orders set placed_at = now() - interval '11 minutes' where id = :i::uuid").param("i", during).update();
        timers.runOnce(Instant.now());
        assertThat(orderStatus(during)).isEqualTo("REJECTED");
        assertThat(faults(during)).isZero();

        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "NONE", "reason", "xong")).andExpect(status().isOk());
        jdbc.sql("update orders set placed_at = now() - interval '11 minutes' where id = :i::uuid").param("i", after).update();
        timers.runOnce(Instant.now());
        assertThat(orderStatus(after)).isEqualTo("REJECTED");
        assertThat(faults(after)).isEqualTo(1);
    }

    @Test
    void onlyTheRightAdministratorsMayChangeItAndEveryChangeIsRecorded() throws Exception {
        String seller = tokenOf(newUser("seller", Role.SELLER), Role.SELLER, Instant.now());
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/app-config")).andExpect(status().isForbidden());
            call(token, put("/api/admin/app-config/maintenance").contentType(MediaType.APPLICATION_JSON).content("{\"level\":\"NONE\",\"reason\":\"x\"}")).andExpect(status().isForbidden());
        }
        // A sign-in older than ten minutes is not enough.
        String stale = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN, Instant.now().minus(Duration.ofMinutes(30)));
        change(stale, "/api/admin/app-config/maintenance", Map.of("level", "NONE", "reason", "x")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        call(stale, get("/api/admin/app-config")).andExpect(status().isOk());

        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "NONE")).andExpect(status().isBadRequest());
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "SOMETIMES", "reason", "x")).andExpect(status().isBadRequest());
        change(admin, "/api/admin/app-config/version-policy/IOS", Map.of("minSupportedVersion", "1.x", "latestVersion", "2.0.0", "reason", "x")).andExpect(status().isBadRequest());
        change(admin, "/api/admin/app-config/version-policy/IOS", Map.of("minSupportedVersion", "3.0.0", "latestVersion", "2.0.0", "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LATEST_BELOW_MINIMUM"));
        change(admin, "/api/admin/app-config/version-policy/WINDOWS", Map.of("minSupportedVersion", "1.0.0", "latestVersion", "2.0.0", "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLATFORM_INVALID"));

        long before = jdbc.sql("select count(*) from app_config_log where reason = 'ghi nhật ký'").query(Long.class).single();
        change(admin, "/api/admin/app-config/version-policy/ANDROID", Map.of("minSupportedVersion", "1.0.0", "latestVersion", "1.2.0", "reason", "ghi nhật ký")).andExpect(status().isOk());
        change(admin, "/api/admin/app-config/maintenance", Map.of("level", "NONE", "reason", "ghi nhật ký")).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from app_config_log where reason = 'ghi nhật ký'").query(Long.class).single()).isEqualTo(before + 2);
    }

    // --- helpers

    private ResultActions change(String token, String path, Map<String, Object> body) throws Exception {
        return call(token, put(path).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("select status from orders where id = :i::uuid").param("i", orderId).query(String.class).single();
    }

    private long faults(String orderId) {
        return jdbc.sql("select count(*) from shop_fault_events where order_id = :i::uuid").param("i", orderId).query(Long.class).single();
    }

    /** A shop with one dish and a customer who can order from it. */
    private Setup shop() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        UUID sellerId = newUser("seller", Role.SELLER);
        String seller = tokenOf(sellerId, Role.SELLER, Instant.now());
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(com.bonbon.backend.ShopAddress.step1("Quán Bảo Trì", "0912345678", "q@example.com"))));
        call(seller, put("/api/merchant/shop/steps/2").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000))));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", lat).param("ln", 151.2).param("o", sellerId).update();
        UUID vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Món\"}")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        String dish = JsonPath.read(call(seller, post("/api/merchant/menu-items").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000)))).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        String address = JsonPath.read(call(customer, post("/api/account/addresses").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("label", "Nhà", "placeId", place,
                "recipientName", "Khách", "recipientPhone", "0987654321", "makeDefault", true)))).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", 151.2).param("i", address).update();
        return new Setup(vendorId, dish, address);
    }

    private class Setup {
        final UUID vendorId;
        final String dish;
        final String address;

        Setup(UUID vendorId, String dish, String address) {
            this.vendorId = vendorId;
            this.dish = dish;
            this.address = address;
        }

        String place() throws Exception {
            Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", address, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dish, "quantity", 1)));
            return JsonPath.read(call(customer, post("/api/orders").header("Idempotency-Key", "key-appcfg-" + System.nanoTime()).contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        }
    }

    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(Map.of("weekday", day, "opensAt", "00:00", "closesAt", "12:00"));
            windows.add(Map.of("weekday", day, "opensAt", "12:00", "closesAt", "00:00"));
        }
        return windows;
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

    private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }
}
