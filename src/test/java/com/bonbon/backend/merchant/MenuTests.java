package com.bonbon.backend.merchant;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.category.CategoryUsage;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An approved shop's menu; approval is simulated in the database (the review API is tested elsewhere). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MenuTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

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
    CategoryUsage usage;

    String seller;
    UUID sellerId;
    String leafId;

    @BeforeEach
    void setUp() throws Exception {
        sellerId = null;
        seller = newSeller();
        leafId = jdbc.sql("select id from categories where level = 3 and active order by name limit 1")
                .query(UUID.class).single().toString();
    }

    @Test
    void onlyAnApprovedShopHasAMenu() throws Exception {
        mvc.perform(get("/api/merchant/menu").header("Authorization", "Bearer " + seller))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        approve(sellerId);
        mvc.perform(get("/api/merchant/menu").header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sections.length()").value(0));
    }

    @Test
    void sectionsAreCreatedRenamedReorderedAndDeleted() throws Exception {
        approve(sellerId);
        String a = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")).andExpect(status().isCreated()), "Cơm");
        String b = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Nước")).andExpect(status().isCreated()), "Nước");

        call(post("/api/merchant/menu-sections"), Map.of("name", "  cơm ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MENU_SECTION_NAME_TAKEN"));

        call(put("/api/merchant/menu-sections/order"), Map.of("ids", List.of(b, a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].name").value("Nước"))
                .andExpect(jsonPath("$.sections[1].name").value("Cơm"));
        call(put("/api/merchant/menu-sections/order"), Map.of("ids", List.of(b))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MENU_ORDER_MISMATCH"));

        call(patch("/api/merchant/menu-sections/" + a), Map.of("name", "Cơm tấm")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].name").value("Cơm tấm"));
        call(delete("/api/merchant/menu-sections/" + b), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(1));
    }

    @Test
    void aSectionWithDishesCannotBeDeletedUntilItIsEmpty() throws Exception {
        approve(sellerId);
        long before = usage.dishCount(UUID.fromString(leafId));
        String section = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String dish = dishId(call(post("/api/merchant/menu-items"), dish(section, "Cơm sườn", 45000)), "Cơm sườn");

        call(delete("/api/merchant/menu-sections/" + section), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECTION_NOT_EMPTY"));
        call(delete("/api/merchant/menu-items/" + dish), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items.length()").value(0));
        call(delete("/api/merchant/menu-sections/" + section), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.length()").value(0));

        // The archived dish is kept for past orders and still counts against its category.
        assertThat(jdbc.sql("select archived_at is not null from menu_items where id = :i").param("i", UUID.fromString(dish))
                .query(Boolean.class).single()).isTrue();
        assertThat(usage.dishCount(UUID.fromString(leafId))).isEqualTo(before + 1);
    }

    @Test
    void dishesNeedALeafCategoryAndAnOwnSection() throws Exception {
        approve(sellerId);
        String section = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String level2 = jdbc.sql("select id from categories where level = 2 limit 1").query(UUID.class).single().toString();

        call(post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", level2, "name", "Cơm", "price", 1000))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_ASSIGNABLE"));
        call(post("/api/merchant/menu-items"), Map.of("sectionId", UUID.randomUUID().toString(), "categoryId", leafId,
                "name", "Cơm", "price", 1000)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MENU_SECTION_NOT_FOUND"));
        call(post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leafId, "name", "Cơm", "price", -1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dishesAreEditedPartiallyAndSoldOutFollowsStock() throws Exception {
        approve(sellerId);
        String s1 = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String s2 = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Nước")), "Nước");
        Map<String, Object> body = new java.util.HashMap<>(dish(s1, "Cơm sườn", 45000));
        body.put("description", "Sườn nướng");
        body.put("stockQuantity", 3);
        String dish = dishId(call(post("/api/merchant/menu-items"), body)
                .andExpect(jsonPath("$.sections[0].items[0].soldOut").value(false)), "Cơm sườn");

        call(patch("/api/merchant/menu-items/" + dish), Map.of("price", 50000, "stockQuantity", 0, "clearDescription", true,
                "sectionId", s2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items.length()").value(0))
                .andExpect(jsonPath("$.sections[1].items[0].price").value(50000))
                .andExpect(jsonPath("$.sections[1].items[0].soldOut").value(true))
                .andExpect(jsonPath("$.sections[1].items[0].description").doesNotExist());

        call(patch("/api/merchant/menu-items/" + dish), Map.of("clearStock", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].items[0].soldOut").value(false))
                .andExpect(jsonPath("$.sections[1].items[0].stockQuantity").doesNotExist());
    }

    @Test
    void dishesCanBeReorderedWithinASection() throws Exception {
        approve(sellerId);
        String s = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String d1 = dishId(call(post("/api/merchant/menu-items"), dish(s, "A", 1000)), "A");
        String d2 = dishId(call(post("/api/merchant/menu-items"), dish(s, "B", 2000)), "B");
        call(put("/api/merchant/menu-sections/" + s + "/items/order"), Map.of("ids", List.of(d2, d1))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].name").value("B"))
                .andExpect(jsonPath("$.sections[0].items[1].name").value("A"));
    }

    @Test
    void aShopCannotTouchAnotherShopsMenu() throws Exception {
        approve(sellerId);
        String section = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String dish = dishId(call(post("/api/merchant/menu-items"), dish(section, "Cơm sườn", 45000)), "Cơm sườn");

        String other = newSeller();
        approve(sellerId);
        mvc.perform(delete("/api/merchant/menu-items/" + dish).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/merchant/menu-sections/" + section).header("Authorization", "Bearer " + other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void aPhotoIsStoredReplacedAndRemoved() throws Exception {
        approve(sellerId);
        String section = id(call(post("/api/merchant/menu-sections"), Map.of("name", "Cơm")), "Cơm");
        String dish = dishId(call(post("/api/merchant/menu-items"), dish(section, "Cơm sườn", 45000)), "Cơm sườn");

        mvc.perform(multipart("/api/merchant/menu-items/" + dish + "/photo")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG)).with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }).header("Authorization", "Bearer " + seller))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sections[0].items[0].photoUrl").isNotEmpty());

        mvc.perform(multipart("/api/merchant/menu-items/" + dish + "/photo")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes())).with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }).header("Authorization", "Bearer " + seller))
                .andExpect(status().isUnsupportedMediaType());

        call(delete("/api/merchant/menu-items/" + dish + "/photo"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].photoUrl").doesNotExist());
    }

    @Test
    void customersHaveNoMenuToManage() throws Exception {
        User c = new User("cust-" + System.nanoTime() + "@example.com", null, "Khách");
        c.addRole(Role.CUSTOMER);
        c.markEmailVerified();
        String customer = tokens.issue(users.saveAndFlush(c), Role.CUSTOMER, Instant.now()).accessToken();
        mvc.perform(get("/api/merchant/menu").header("Authorization", "Bearer " + customer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/merchant/menu-sections").header("Authorization", "Bearer " + customer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
    }

    // --- helpers

    private String newSeller() throws Exception {
        User u = new User("menu-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        sellerId = u.getId();
        String token = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        mvc.perform(put("/api/merchant/shop/steps/1").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("name", "Cơm Tấm",
                        "phone", "0912345678", "email", "com@example.com", "placeId", place)))).andExpect(status().isOk());
        return token;
    }

    private void approve(UUID owner) {
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o")
                .param("o", owner).update();
    }

    private Map<String, Object> dish(String sectionId, String name, int price) {
        return Map.of("sectionId", sectionId, "categoryId", leafId, "name", name, "price", price);
    }

    /** The id of the section called {@code name} in the response. */
    private static String id(ResultActions result, String name) throws Exception {
        List<String> ids = JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.sections[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    /** The id of the dish called {@code name} in the response. */
    private static String dishId(ResultActions result, String name) throws Exception {
        List<String> ids = JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.sections[*].items[?(@.name=='" + name + "')].id");
        return ids.get(0);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + seller);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
