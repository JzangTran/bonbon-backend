package com.bonbon.backend.ordering.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestImages;
import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.bonbon.backend.ordering.order.service.HomeFeedService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer home page: discounted dishes, display categories, the shop feed with and without a position, hidden and
 * favourite shops, shop logos. Each test gets its own leaf category and display category, and its own spot in the southern ocean,
 * so the shared database does not leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class HomeFeedTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double KM = 1 / 111.0;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    HomeFeedService feed;

    String leaf;
    String display;
    String admin;
    String customer;
    double baseLat;
    final double baseLng = 150.3;

    @BeforeEach
    void setUp() throws Exception {
        feed.clearCache();
        UUID parent = jdbc.sql("select id from categories where level = 2 order by name limit 1").query(UUID.class).single();
        leaf = jdbc.sql("insert into categories (parent_id, level, name) values (:p, 3, :n) returning id")
                .param("p", parent).param("n", "Lá feed " + System.nanoTime()).query(UUID.class).single().toString();
        admin = token(Role.ADMIN);
        customer = token(Role.CUSTOMER);
        display = JsonPath.read(call(admin, post("/api/admin/display-categories"), Map.of("name", "Trà sữa", "leafIds", List.of(leaf)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        baseLat = -70 + (System.nanoTime() % 1000) * 0.01;
    }

    @Test
    void aDiscountShowsTheOriginalPriceAndTheWholePercentOff() throws Exception {
        String shop = shop("Quán giảm", baseLat, baseLng, 2, 40_000, 47_000);
        feedAt(baseLat, baseLng).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].shop.id").value(shop))
                .andExpect(jsonPath("$.items[0].dishes[0].price").value(40_000))
                .andExpect(jsonPath("$.items[0].dishes[0].originalPrice").value(47_000))
                .andExpect(jsonPath("$.items[0].dishes[0].discountPercent").value(14))   // 14.89 rounds down
                .andExpect(jsonPath("$.dealMaxPercent").value(14));

        // the original price has to be above what the customer pays
        String item = jdbc.sql("select id from menu_items where vendor_id = :v").param("v", UUID.fromString(shop)).query(UUID.class).single().toString();
        call(tokenOf(shop), patch("/api/merchant/menu-items/" + item), Map.of("originalPrice", 40_000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORIGINAL_PRICE_NOT_ABOVE"));
        call(tokenOf(shop), patch("/api/merchant/menu-items/" + item), Map.of("clearOriginalPrice", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].items[0].discountPercent").value(0));
    }

    @Test
    void withAPositionOnlyShopsWhoseRadiusCoversItNearestFirstTenAtATime() throws Exception {
        for (int i = 0; i < 11; i++) {
            shop("Gần " + i, baseLat + (0.1 + i * 0.05) * KM, baseLng, 2, 30_000, null);
        }
        shop("Ngoài", baseLat + 1.5 * KM, baseLng, 1, 30_000, null);   // 1.5 km away, delivers 1 km

        feedAt(baseLat, baseLng).andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].shop.name").value("Gần 0"));
        feedAt(baseLat, baseLng, "page", "1").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].shop.name").value("Gần 10"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void withoutAPositionEveryShopBestRatedFirst() throws Exception {
        String few = shop("Một đánh giá 5 sao", baseLat, baseLng, 2, 30_000, null);
        String many = shop("Nhiều đánh giá 4.6", baseLat, baseLng, 2, 30_000, null);
        String low = shop("Ba sao", baseLat, baseLng, 2, 30_000, null);
        rate(few, 5, 1);
        rate(many, 460, 100);
        rate(low, 30, 10);

        mvc.perform(get("/api/feed").param("displayCategoryId", display)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].shop.id").value(many))
                .andExpect(jsonPath("$.items[1].shop.id").value(few))
                .andExpect(jsonPath("$.items[2].shop.id").value(low))
                .andExpect(jsonPath("$.items[0].shop.distanceKm").doesNotExist());
    }

    @Test
    void dealsListsOnlyDiscountedShopsBiggestFirst() throws Exception {
        shop("Không giảm", baseLat, baseLng, 2, 30_000, null);
        String ten = shop("Giảm 10", baseLat, baseLng, 2, 45_000, 50_000);
        String half = shop("Giảm 50", baseLat, baseLng, 2, 25_000, 50_000);

        feedAt(baseLat, baseLng, "deals", "true").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].shop.id").value(half))
                .andExpect(jsonPath("$.items[1].shop.id").value(ten))
                .andExpect(jsonPath("$.dealMaxPercent").value(50));
    }

    @Test
    void aDisplayCategoryListsShopsWithDishesInItsLeaves() throws Exception {
        String in = shop("Trong danh mục", baseLat, baseLng, 2, 30_000, null);
        String other = jdbc.sql("select id from categories where level = 3 and active and id <> :l::uuid order by name limit 1")
                .param("l", leaf).query(UUID.class).single().toString();
        String out = shop("Ngoài danh mục", baseLat, baseLng, 2, 30_000, null);
        jdbc.sql("update menu_items set category_id = :c::uuid where vendor_id = :v").param("c", other).param("v", UUID.fromString(out)).update();
        feed.clearCache();

        mvc.perform(get("/api/display-categories")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + display + "')].name").value("Trà sữa"));
        mvc.perform(get("/api/feed").param("lat", String.valueOf(baseLat)).param("lng", String.valueOf(baseLng))
                        .param("displayCategoryId", display))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].shop.id").value(in));

        // hidden display categories match nothing; only level-3 categories can be gathered
        call(admin, patch("/api/admin/display-categories/" + display), Map.of("active", false)).andExpect(status().isOk());
        feed.clearCache();
        mvc.perform(get("/api/feed").param("displayCategoryId", display)).andExpect(jsonPath("$.total").value(0));
        String level2 = jdbc.sql("select id from categories where level = 2 limit 1").query(UUID.class).single().toString();
        call(admin, patch("/api/admin/display-categories/" + display), Map.of("leafIds", List.of(level2)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DISPLAY_CATEGORY_LEAF_INVALID"));
        call(customer, get("/api/admin/display-categories"), null).andExpect(status().isForbidden());
    }

    @Test
    void aHiddenShopLeavesTheCustomersFeedForThirtyDaysAndAGuestSendsTheirOwn() throws Exception {
        String hidden = shop("Ẩn", baseLat, baseLng, 2, 30_000, null);
        String kept = shop("Giữ", baseLat + 0.2 * KM, baseLng, 2, 30_000, null);

        call(customer, put("/api/account/hidden-shops/" + hidden), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenUntil").exists());
        call(customer, feedRequest(baseLat, baseLng), null).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].shop.id").value(kept));
        // another customer still sees it; a guest leaves out what they hid on their phone
        feedAt(baseLat, baseLng).andExpect(jsonPath("$.total").value(2));
        feedAt(baseLat, baseLng, "hide", kept).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].shop.id").value(hidden));

        jdbc.sql("update hidden_shops set hidden_until = now() - interval '1 minute' where vendor_id = :v").param("v", UUID.fromString(hidden)).update();
        call(customer, feedRequest(baseLat, baseLng), null).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void favouriteShopsComeWithTheirDishesNewestFirst() throws Exception {
        String a = shop("Yêu A", baseLat, baseLng, 2, 30_000, null);
        String b = shop("Yêu B", baseLat, baseLng, 2, 30_000, null);
        call(customer, put("/api/account/favorite-shops/" + a), null).andExpect(status().isNoContent());
        Thread.sleep(5);
        call(customer, put("/api/account/favorite-shops/" + b), null).andExpect(status().isNoContent());
        call(customer, put("/api/account/favorite-shops/" + b), null).andExpect(status().isNoContent());   // again: no change

        call(customer, get("/api/account/favorite-shops"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].shop.id").value(b))
                .andExpect(jsonPath("$[0].dishes[0].name").exists());
        call(customer, delete("/api/account/favorite-shops/" + b), null).andExpect(status().isNoContent());
        call(customer, get("/api/account/favorite-shops"), null).andExpect(jsonPath("$.length()").value(1));
        call(customer, put("/api/account/favorite-shops/" + UUID.randomUUID()), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
        mvc.perform(get("/api/account/favorite-shops")).andExpect(status().isUnauthorized());
    }

    @Test
    void aShopLogoShowsOnItsCard() throws Exception {
        String shop = shop("Có logo", baseLat, baseLng, 2, 30_000, null);
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", TestImages.PNG);
        mvc.perform(multipart("/api/merchant/shop/logo").file(file).with(r -> {
                    r.setMethod("PUT");
                    return r;
                }).header("Authorization", "Bearer " + tokenOf(shop)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.shop.logoUrl").exists());
        feedAt(baseLat, baseLng).andExpect(jsonPath("$.items[0].shop.logoUrl").exists());
        call(tokenOf(shop), delete("/api/merchant/shop/logo"), null).andExpect(jsonPath("$.shop.logoUrl").doesNotExist());
    }

    // --- helpers

    private final Map<String, String> tokensByShop = new java.util.HashMap<>();

    private String tokenOf(String shopId) {
        return tokensByShop.get(shopId);
    }

    private void rate(String shopId, int sum, int count) {
        jdbc.sql("update vendors set rating_sum = :s, rating_count = :c where id = :v")
                .param("s", sum).param("c", count).param("v", UUID.fromString(shopId)).update();
        feed.clearCache();
    }

    private String shop(String name, double lat, double lng, double radiusKm, int price, Integer originalPrice) throws Exception {
        User u = new User("feed-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        String token = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        call(token, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1(name, "0912345678", "b@example.com")).andExpect(status().isOk());
        call(token, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", radiusKm,
                "deliveryFee", 10000)).andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", u.getId()).update();
        String section = JsonPath.read(call(token, post("/api/merchant/menu-sections"), Map.of("name", "Món"))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        Map<String, Object> dish = new java.util.HashMap<>(Map.of("sectionId", section, "categoryId", leaf, "name", name + " món", "price", price));
        if (originalPrice != null) {
            dish.put("originalPrice", originalPrice);
        }
        call(token, post("/api/merchant/menu-items"), dish).andExpect(status().isCreated());
        String id = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", u.getId()).query(UUID.class).single().toString();
        tokensByShop.put(id, token);
        feed.clearCache();
        return id;
    }

    private MockHttpServletRequestBuilder feedRequest(double lat, double lng) {
        return get("/api/feed").param("lat", String.valueOf(lat)).param("lng", String.valueOf(lng)).param("displayCategoryId", display);
    }

    private ResultActions feedAt(double lat, double lng, String... params) throws Exception {
        MockHttpServletRequestBuilder request = feedRequest(lat, lng);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private String token(Role role) {
        User user = new User(role.name().toLowerCase() + "-feed-" + System.nanoTime() + "@example.com", null, "Người dùng");
        user.addRole(role);
        user.markEmailVerified();
        return tokens.issue(users.saveAndFlush(user), role, Instant.now()).accessToken();
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
