package com.bonbon.backend.marketplace.category;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.bonbon.backend.marketplace.category.service.DishCategoryModerationService;
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
 * Dish category moderation (flows/category/moderate-dish-category.md and its siblings). Each test builds its own shop at its own
 * made-up position and searches with its own word, so the shared database does not leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DishCategoryModerationTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
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

    @Autowired
    DishCategoryModerationService moderation;

    String admin;
    String cheap;
    String pricey;
    String other;
    String word;
    double baseLat;
    final double baseLng = 152.4;

    @BeforeEach
    void setUp() {
        List<UUID> leaves = jdbc.sql("select id from categories where level = 3 and active order by name limit 3").query(UUID.class).list();
        cheap = leaves.get(0).toString();
        pricey = leaves.get(1).toString();
        other = leaves.get(2).toString();
        word = "zq" + Long.toString(System.nanoTime(), 36);
        baseLat = -80 + (System.nanoTime() % 1000) * 0.01;
        admin = token(Role.ADMIN, "mod-admin");
    }

    @Test
    void aFlaggedDishLeavesSearchAndCategoryFilterButStaysOnTheMenuAndInTheAreaList() throws Exception {
        Shop shop = shop("Quán A");
        browse("q", word).andExpect(jsonPath("$.total").value(1));
        browse("categoryId", cheap).andExpect(jsonPath("$.items[?(@.name=='" + shop.name + "')]").isNotEmpty());

        flag(shop.dish, pricey, "Món này thuộc ngành khác", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESTRICTED"))
                .andExpect(jsonPath("$.originalCategory.id").value(cheap))
                .andExpect(jsonPath("$.suggestedCategory.id").value(pricey))
                .andExpect(jsonPath("$.shopName").value(shop.name));

        browse("q", word).andExpect(jsonPath("$.total").value(0));
        browse("categoryId", cheap).andExpect(jsonPath("$.items[?(@.name=='" + shop.name + "')]").isEmpty());
        // not hidden from the shop's own area list, its menu, or ordering
        browse().andExpect(jsonPath("$.items[?(@.name=='" + shop.name + "')]").isNotEmpty());
        mvc.perform(get("/api/vendors/" + shop.id + "/menu")).andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].items[0].name").value(word + " Cơm"));
        assertThat(notifications(shop.owner, "SHOP_DISH_FLAGGED")).isEqualTo(1);
    }

    @Test
    void onlyAdministratorsWithDishModerateFlagAndEachDishHasOneOpenCase() throws Exception {
        Shop shop = shop("Quán A");
        mvc.perform(post("/api/admin/dishes/" + shop.dish + "/flag").header("Authorization", "Bearer " + shop.token).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("reason", "x", "suggestedCategoryId", pricey)))).andExpect(status().isForbidden());
        flag(shop.dish, cheap, "trùng", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SAME_CATEGORY"));
        flag(shop.dish, UUID.randomUUID().toString(), "lạ", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_ASSIGNABLE"));
        flag(shop.dish, pricey, "quá khứ", Instant.now().minus(1, ChronoUnit.HOURS)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEADLINE_IN_THE_PAST"));
        flag(shop.dish, pricey, "xa", Instant.now().plus(10, ChronoUnit.DAYS)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DEADLINE_TOO_FAR"));
        flag(UUID.randomUUID().toString(), pricey, "không có", null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DISH_NOT_FOUND"));

        flag(shop.dish, pricey, "lần 1", null).andExpect(status().isCreated());
        flag(shop.dish, other, "lần 2", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_OPEN"));
    }

    @Test
    void theSellerFixesTheCategoryAndTheDishComesBack() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));

        mvc.perform(auth(get("/api/merchant/category-cases"), shop.token)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(id));
        // another shop sees nothing of it
        Shop stranger = shop("Quán B");
        mvc.perform(auth(get("/api/merchant/category-cases/" + id), stranger.token)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
        send(post("/api/merchant/category-cases/" + id + "/resolve"), stranger.token, Map.of("categoryId", pricey)).andExpect(status().isNotFound());

        // a category the shop picks that is not the suggestion still closes the case
        send(post("/api/merchant/category-cases/" + id + "/resolve"), shop.token, Map.of("categoryId", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.finalCategory.id").value(other));
        assertThat(categoryOf(shop.dish)).isEqualTo(other);
        browse("q", word).andExpect(jsonPath("$.items[?(@.name=='Quán A')]").isNotEmpty());
        send(post("/api/merchant/category-cases/" + id + "/resolve"), shop.token, Map.of("categoryId", pricey)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_OPEN")).andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void editingTheCategoryOnTheMenuAnswersTheCaseToo() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));

        send(patch("/api/merchant/menu-items/" + shop.dish), shop.token, Map.of("categoryId", pricey)).andExpect(status().isOk());

        mvc.perform(auth(get("/api/admin/category-cases/" + id), admin)).andExpect(jsonPath("$.status").value("RESOLVED"));
        browse("q", word).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void pastTheDeadlineTheSystemAppliesTheSuggestionAndTheSellerMayConfirm() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));
        moderation.runDue(Instant.now().plus(2, ChronoUnit.DAYS));

        mvc.perform(auth(get("/api/merchant/category-cases/" + id), shop.token)).andExpect(jsonPath("$.status").value("AUTO_CORRECTED"))
                .andExpect(jsonPath("$.finalCategory.id").value(pricey)).andExpect(jsonPath("$.appealDeadlineAt").isNotEmpty());
        assertThat(categoryOf(shop.dish)).isEqualTo(pricey);
        browse("q", word).andExpect(jsonPath("$.total").value(1));
        assertThat(notifications(shop.owner, "SHOP_DISH_AUTO_CORRECTED")).isEqualTo(1);

        mvc.perform(auth(post("/api/merchant/category-cases/" + id + "/confirm"), shop.token)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(auth(post("/api/merchant/category-cases/" + id + "/confirm"), shop.token)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_OPEN"));
    }

    @Test
    void aCorrectionNobodyAnswersExpires() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));
        moderation.runDue(Instant.now().plus(2, ChronoUnit.DAYS));
        jdbc.sql("update dish_category_cases set appeal_deadline_at = now() - interval '1 minute' where id = :id").param("id", UUID.fromString(id)).update();

        // too late even before the job has run
        send(post("/api/merchant/category-cases/" + id + "/appeal"), shop.token, Map.of("categoryId", cheap, "note", "muộn")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_OPEN"));
        moderation.runDue(Instant.now());
        mvc.perform(auth(get("/api/admin/category-cases/" + id), admin)).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(categoryOf(shop.dish)).isEqualTo(pricey);
    }

    @Test
    void anAcceptedAppealMovesTheDishToTheCategoryTheShopAskedFor() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));
        moderation.runDue(Instant.now().plus(2, ChronoUnit.DAYS));

        send(post("/api/merchant/category-cases/" + id + "/appeal"), shop.token, Map.of("categoryId", other, "note", "Đây mới là ngành đúng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPEALED")).andExpect(jsonPath("$.requestedCategory.id").value(other));
        // waiting does not change the category the system set
        assertThat(categoryOf(shop.dish)).isEqualTo(pricey);
        mvc.perform(auth(get("/api/admin/category-cases?status=APPEALED&vendorId=" + shop.id), admin)).andExpect(jsonPath("$.total").value(1));
        // a second appeal on the same case is refused
        send(post("/api/merchant/category-cases/" + id + "/appeal"), shop.token, Map.of("categoryId", other, "note", "lại")).andExpect(status().isConflict());
        // the administrator cannot reclassify while the appeal waits
        send(post("/api/admin/dishes/" + shop.dish + "/reclassify"), admin, Map.of("categoryId", cheap, "reason", "vội")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPEAL_PENDING"));

        send(post("/api/admin/category-cases/" + id + "/decide"), admin, Map.of("decision", "ACCEPT", "reason", "Hợp lý")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPEAL_ACCEPTED")).andExpect(jsonPath("$.finalCategory.id").value(other));
        assertThat(categoryOf(shop.dish)).isEqualTo(other);
        assertThat(notifications(shop.owner, "SHOP_DISH_APPEAL_ACCEPTED")).isEqualTo(1);
        send(post("/api/admin/category-cases/" + id + "/decide"), admin, Map.of("decision", "REJECT", "reason", "đổi ý")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_APPEALED"));
    }

    @Test
    void aRejectedAppealKeepsTheSystemsCategory() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));
        moderation.runDue(Instant.now().plus(2, ChronoUnit.DAYS));
        send(post("/api/merchant/category-cases/" + id + "/appeal"), shop.token, Map.of("categoryId", cheap, "note", "Xin giữ")).andExpect(status().isOk());

        send(post("/api/admin/category-cases/" + id + "/decide"), admin, Map.of("decision", "REJECT", "reason", "Không đúng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPEAL_REJECTED")).andExpect(jsonPath("$.decisionReason").value("Không đúng"));
        assertThat(categoryOf(shop.dish)).isEqualTo(pricey);
        assertThat(notifications(shop.owner, "SHOP_DISH_APPEAL_REJECTED")).isEqualTo(1);
    }

    @Test
    void anAdministratorReclassifiesAtOnceAndClosesTheOpenCase() throws Exception {
        Shop shop = shop("Quán A");
        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));

        send(post("/api/admin/dishes/" + shop.dish + "/reclassify"), admin, Map.of("categoryId", other, "reason", "Rõ ràng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertThat(categoryOf(shop.dish)).isEqualTo(other);
        browse("q", word).andExpect(jsonPath("$.total").value(1));
        assertThat(notifications(shop.owner, "SHOP_DISH_RECLASSIFIED")).isEqualTo(1);

        // a dish with no case can be reclassified too
        Shop plain = shop("Quán C");
        send(post("/api/admin/dishes/" + plain.dish + "/reclassify"), admin, Map.of("categoryId", pricey, "reason", "Sửa")).andExpect(status().isOk());
        assertThat(categoryOf(plain.dish)).isEqualTo(pricey);
    }

    @Test
    void theQueueListsOldestAppealsFirstAndFiltersByShop() throws Exception {
        Shop shop = shop("Quán A");
        flag(shop.dish, pricey, "Sai ngành", null).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/admin/category-cases?vendorId=" + shop.id), admin)).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].dishName").value(word + " Cơm"));
        mvc.perform(auth(get("/api/admin/category-cases?status=NOPE"), admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        mvc.perform(auth(get("/api/admin/category-cases?size=500"), admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mvc.perform(auth(get("/api/admin/category-cases"), shop.token)).andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorFindsADishWithItsCategoryPathAndOpenCase() throws Exception {
        Shop shop = shop("Quán A");
        mvc.perform(auth(get("/api/admin/merchants/" + shop.id + "/dishes"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("Quán A")).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value(word + " Cơm")).andExpect(jsonPath("$.items[0].categoryId").value(cheap))
                .andExpect(jsonPath("$.items[0].categoryPath").value(org.hamcrest.Matchers.containsString("›")))
                .andExpect(jsonPath("$.items[0].restricted").value(false)).andExpect(jsonPath("$.items[0].openCase").doesNotExist());

        String id = caseId(flag(shop.dish, pricey, "Sai ngành", null));
        mvc.perform(auth(get("/api/admin/merchants/" + shop.id + "/dishes"), admin)).andExpect(jsonPath("$.items[0].restricted").value(true))
                .andExpect(jsonPath("$.items[0].openCase.id").value(id)).andExpect(jsonPath("$.items[0].openCase.status").value("RESTRICTED"));

        mvc.perform(auth(get("/api/admin/merchants/" + UUID.randomUUID() + "/dishes"), admin)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_FOUND"));
        mvc.perform(auth(get("/api/admin/merchants/" + shop.id + "/dishes"), shop.token)).andExpect(status().isForbidden());
    }

    // --- helpers

    private record Shop(String id, String name, String dish, String token, UUID owner) {
    }

    /** An approved shop at its own position with one dish, in the {@code cheap} category, named after the test's word. */
    private Shop shop(String name) throws Exception {
        User u = new User("mod-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        String token = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        send(put("/api/merchant/shop/steps/1"), token, com.bonbon.backend.ShopAddress.step1(name, "0912345678", "b@example.com")).andExpect(status().isOk());
        send(put("/api/merchant/shop/steps/2"), token, Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000)).andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", baseLat).param("ln", baseLng).param("o", u.getId()).update();
        String section = JsonPath.read(send(post("/api/merchant/menu-sections"), token, Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        send(post("/api/merchant/menu-items"), token, Map.of("sectionId", section, "categoryId", cheap, "name", word + " Cơm", "price", 30000)).andExpect(status().isCreated());
        String id = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", u.getId()).query(UUID.class).single().toString();
        String dish = jdbc.sql("select id from menu_items where vendor_id = :v").param("v", UUID.fromString(id)).query(UUID.class).single().toString();
        return new Shop(id, name, dish, token, u.getId());
    }

    private ResultActions flag(String dishId, String suggested, String reason, Instant deadline) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("reason", reason, "suggestedCategoryId", suggested));
        if (deadline != null) {
            body.put("deadlineAt", deadline.toString());
        }
        return send(post("/api/admin/dishes/" + dishId + "/flag"), admin, body);
    }

    private static String caseId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String categoryOf(String dishId) {
        return jdbc.sql("select category_id from menu_items where id = :id").param("id", UUID.fromString(dishId)).query(UUID.class).single().toString();
    }

    private int notifications(UUID recipient, String type) {
        return jdbc.sql("select count(*) from notifications where recipient_id = :r and audience = 'SHOP' and type = :t").param("r", recipient).param("t", type).query(Integer.class).single();
    }

    private ResultActions browse(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/vendors").param("lat", String.valueOf(baseLat)).param("lng", String.valueOf(baseLng));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(auth(request, token).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return send(request, admin, body);
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(Role role, String prefix) {
        User user = new User(prefix + "-" + System.nanoTime() + "@example.com", null, "Người dùng");
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
}
