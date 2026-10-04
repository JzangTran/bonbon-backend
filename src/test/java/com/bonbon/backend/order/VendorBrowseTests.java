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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public browsing. Shops are placed at made-up coordinates far from every other test's shops, and each test
 * searches with its own unique word, so the shared database does not leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VendorBrowseTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** About 1 km of latitude. */
    private static final double KM = 1 / 111.0;

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
    String word;
    double baseLat;
    final double baseLng = 151.2;

    @BeforeEach
    void setUp() {
        leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1")
                .query(UUID.class).single().toString();
        word = "zz" + Long.toString(System.nanoTime(), 36);
        // Each test lands at its own latitude somewhere in the southern ocean, away from every other test.
        baseLat = -80 + (System.nanoTime() % 1000) * 0.01;
    }

    @Test
    void listsOnlyShopsWhoseOwnRadiusCoversThePointNearestFirst() throws Exception {
        shop(word + " Gần", baseLat + 0.5 * KM, baseLng, 2, "Cơm tấm");
        shop(word + " Xa", baseLat + 1.5 * KM, baseLng, 2, "Bún bò");
        shop(word + " Ngoài", baseLat + 1.5 * KM, baseLng, 1, "Phở");   // radius 1 km, 1.5 km away

        list("q", word).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").value(word + " Gần"))
                .andExpect(jsonPath("$.items[1].name").value(word + " Xa"))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.5))
                .andExpect(jsonPath("$.items[0].open").value(true));
    }

    @Test
    void searchIgnoresCaseAndDiacriticsOnShopAndDishNames() throws Exception {
        shop(word + " Quán A", baseLat, baseLng, 2, "Phở bò");
        shop(word + " Quán B", baseLat, baseLng, 2, "Bánh mì");

        list("q", "pho bo").andExpect(jsonPath("$.items[?(@.name=='" + word + " Quán A')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.name=='" + word + " Quán B')]").isEmpty());
        list("q", (word + " QUAN b").toUpperCase()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value(word + " Quán B"));
        list("q", "%").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void closedAndPausedShopsAreListedLastAndMarkedClosed() throws Exception {
        String paused = shop(word + " A", baseLat, baseLng, 2, "Cơm");
        shop(word + " B", baseLat + 1 * KM, baseLng, 2, "Cơm");
        jdbc.sql("update vendors set accepting_orders = false where id = :i").param("i", UUID.fromString(paused)).update();

        list("q", word).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].name").value(word + " B"))
                .andExpect(jsonPath("$.items[0].open").value(true))
                .andExpect(jsonPath("$.items[1].name").value(word + " A"))
                .andExpect(jsonPath("$.items[1].open").value(false));
    }

    @Test
    void shopsWithoutAnOrderableDishOrNotApprovedAreHidden() throws Exception {
        String soldOut = shop(word + " Hết", baseLat, baseLng, 2, "Cơm");
        jdbc.sql("update menu_items set status = 'SOLD_OUT' where vendor_id = :v").param("v", UUID.fromString(soldOut)).update();
        String suspended = shop(word + " Treo", baseLat, baseLng, 2, "Cơm");
        jdbc.sql("update vendors set status = 'SUSPENDED' where id = :v").param("v", UUID.fromString(suspended)).update();
        shop(word + " Ổn", baseLat, baseLng, 2, "Cơm");

        list("q", word).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value(word + " Ổn"));
    }

    @Test
    void categoryFilterMatchesTheNodeAndItsDescendants() throws Exception {
        shop(word + " Cơm", baseLat, baseLng, 2, "Cơm");
        String other = jdbc.sql("select id from categories where level = 3 and active and id <> :l::uuid limit 1")
                .param("l", leaf).query(UUID.class).single().toString();
        String shopB = shop(word + " Khác", baseLat, baseLng, 2, "Gì đó");
        jdbc.sql("update menu_items set category_id = :c::uuid where vendor_id = :v").param("c", other)
                .param("v", UUID.fromString(shopB)).update();
        String parent = jdbc.sql("select parent_id from categories where id = :l::uuid").param("l", leaf)
                .query(UUID.class).single().toString();

        list("q", word, "categoryId", leaf).andExpect(jsonPath("$.items[?(@.name=='" + word + " Cơm')]").isNotEmpty());
        list("q", word, "categoryId", other).andExpect(jsonPath("$.items[?(@.name=='" + word + " Khác')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.name=='" + word + " Cơm')]").isEmpty());
        list("q", word, "categoryId", parent).andExpect(jsonPath("$.items[?(@.name=='" + word + " Cơm')]").isNotEmpty());
        list("q", word, "categoryId", UUID.randomUUID().toString()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void resultsArePaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            shop(word + " Q" + i, baseLat + i * 0.1 * KM, baseLng, 2, "Cơm");
        }
        list("q", word, "size", "2").andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.total").value(3));
        list("q", word, "size", "2", "page", "1").andExpect(jsonPath("$.items.length()").value(1));
        list("q", word, "sort", "name").andExpect(jsonPath("$.items[0].name").value(word + " Q0"));
        list("q", word, "size", "500").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        list("q", word, "sort", "rating").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mvc.perform(get("/api/vendors").param("lat", "95").param("lng", "10")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POSITION"));
    }

    @Test
    void theMenuShowsSectionsDishesOptionsAndSoldOutFlagsWithoutLogin() throws Exception {
        String shopId = shop(word + " Menu", baseLat, baseLng, 2, "Trà sữa");
        String token = tokenOf(shopId);
        String group = JsonPath.read(call(token, post("/api/merchant/option-groups"), Map.of("name", "Size", "min", 1, "max", 1,
                "options", List.of(Map.of("name", "M", "priceDelta", 0, "defaultChoice", true),
                        Map.of("name", "L", "priceDelta", 5000)))).andReturn().getResponse().getContentAsString(),
                "$.groups[0].id");
        String dish = JsonPath.read(call(token, get("/api/merchant/menu"), null).andReturn().getResponse().getContentAsString(),
                "$.sections[0].items[0].id");
        call(token, put("/api/merchant/menu-items/" + dish + "/option-groups"), Map.of("groupIds", List.of(group)))
                .andExpect(status().isOk());
        call(token, patch("/api/merchant/menu-items/" + dish), Map.of("stockQuantity", 4)).andExpect(status().isOk());
        call(token, post("/api/merchant/menu-items"), Map.of("sectionId",
                JsonPath.read(call(token, get("/api/merchant/menu"), null).andReturn().getResponse().getContentAsString(),
                        "$.sections[0].id"), "categoryId", leaf, "name", "Hết hàng", "price", 1000)).andExpect(status().isCreated());
        jdbc.sql("update menu_items set status = 'SOLD_OUT' where name = 'Hết hàng' and vendor_id = :v")
                .param("v", UUID.fromString(shopId)).update();

        mvc.perform(get("/api/vendors/" + shopId + "/menu").param("lat", String.valueOf(baseLat + KM))
                        .param("lng", String.valueOf(baseLng)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shop.name").value(word + " Menu"))
                .andExpect(jsonPath("$.shop.distanceKm").value(1.0))
                .andExpect(jsonPath("$.sections[0].items.length()").value(2))
                .andExpect(jsonPath("$.sections[0].items[?(@.name=='Trà sữa')].soldOut").value(false))
                .andExpect(jsonPath("$.sections[0].items[?(@.name=='Trà sữa')].optionGroups[0].name").value("Size"))
                .andExpect(jsonPath("$.sections[0].items[?(@.name=='Trà sữa')].optionGroups[0].options.length()").value(2))
                .andExpect(jsonPath("$.sections[0].items[?(@.name=='Hết hàng')].soldOut").value(true))
                .andExpect(jsonPath("$.sections[0].items[0].stockQuantity").doesNotExist());
        mvc.perform(get("/api/vendors/" + shopId + "/menu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shop.distanceKm").doesNotExist());
    }

    @Test
    void aShopThatIsNotApprovedHasNoPublicMenu() throws Exception {
        String shopId = shop(word + " Treo", baseLat, baseLng, 2, "Cơm");
        jdbc.sql("update vendors set status = 'PENDING' where id = :v").param("v", UUID.fromString(shopId)).update();
        mvc.perform(get("/api/vendors/" + shopId + "/menu")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
        mvc.perform(get("/api/vendors/" + UUID.randomUUID() + "/menu")).andExpect(status().isNotFound());
    }

    // --- helpers

    private final java.util.Map<String, String> tokensByShop = new java.util.HashMap<>();

    private String tokenOf(String shopId) {
        return tokensByShop.get(shopId);
    }

    /** An approved shop at the given position with one dish named {@code dishName}; returns the vendor id. */
    private String shop(String name, double lat, double lng, double radiusKm, String dishName) throws Exception {
        User u = new User("browse-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        String token = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(token, put("/api/merchant/shop/steps/1"), Map.of("name", name, "phone", "0912345678",
                "email", "b@example.com", "placeId", place)).andExpect(status().isOk());
        call(token, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", radiusKm,
                "deliveryFee", 10000)).andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", u.getId()).update();
        String section = JsonPath.read(call(token, post("/api/merchant/menu-sections"), Map.of("name", "Món"))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        call(token, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", dishName,
                "price", 30000)).andExpect(status().isCreated());
        String id = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", u.getId()).query(UUID.class)
                .single().toString();
        tokensByShop.put(id, token);
        return id;
    }

    private ResultActions list(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/vendors").param("lat", String.valueOf(baseLat))
                .param("lng", String.valueOf(baseLng));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
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
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
