package com.bonbon.backend.category;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CategoryTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    CategoryCatalog catalog;

    /** Stands in for the merchant module: which categories have dishes. */
    @MockitoBean
    CategoryUsage usage;

    String admin;
    String customer;
    String rootId;

    @BeforeEach
    void setUp() throws Exception {
        when(usage.dishCount(any())).thenReturn(0L);
        admin = token(Role.ADMIN);
        customer = token(Role.CUSTOMER);
        rootId = JsonPath.read(mvc.perform(get("/api/categories")).andReturn().getResponse().getContentAsString(), "$[0].id");
    }

    @Test
    void publicTreeHasTheFixedRootAndTheSeededLevels() throws Exception {
        mvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Thực phẩm và đồ uống"))
                .andExpect(jsonPath("$[0].level").value(1))
                .andExpect(jsonPath("$[0].children[?(@.name == 'Đồ uống')].children[?(@.name == 'Cà phê')].level").value(3));
    }

    @Test
    void managementNeedsCategoryWrite() throws Exception {
        mvc.perform(get("/api/admin/categories")).andExpect(status().isUnauthorized());
        mvc.perform(auth(get("/api/admin/categories"), customer)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/admin/categories"), admin)).andExpect(status().isOk());
    }

    @Test
    void createsLevelsTwoAndThreeButNeverAFourth() throws Exception {
        String l2 = create(rootId, unique("Đặc sản"), null);
        String l3 = create(l2, unique("Bánh cuốn"), null);
        assertThat(catalog.isAssignableLeaf(UUID.fromString(l3))).isTrue();
        assertThat(catalog.isAssignableLeaf(UUID.fromString(l2))).isFalse();

        send(post("/api/admin/categories"), Map.of("parentId", l3, "name", "Cấp bốn"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_DEPTH_EXCEEDED"));
    }

    @Test
    void siblingNamesAreUniqueIgnoringCase() throws Exception {
        String name = unique("Món chay");
        String l2 = create(rootId, name, null);
        send(post("/api/admin/categories"), Map.of("parentId", rootId, "name", "  " + name.toUpperCase() + " "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
        // The same name under a different parent is fine.
        create(l2, name, null);
    }

    @Test
    void rootIsFixedExceptForItsRate() throws Exception {
        send(patch("/api/admin/categories/" + rootId), Map.of("name", "Khác")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_ROOT_FIXED"));
        send(patch("/api/admin/categories/" + rootId), Map.of("active", false)).andExpect(status().isConflict());
        mvc.perform(auth(delete("/api/admin/categories/" + rootId), admin)).andExpect(status().isConflict());
    }

    @Test
    void commissionRateIsInheritedUntilOverridden() throws Exception {
        String l2 = create(rootId, unique("Đồ nướng"), "12.5");
        String l3 = create(l2, unique("Xiên que"), null);
        assertThat(catalog.effectiveCommissionRate(UUID.fromString(l3))).hasValueSatisfying(r -> assertThat(r).isEqualByComparingTo("12.5"));

        send(patch("/api/admin/categories/" + l3), Map.of("commissionRate", 8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commissionRate").value(8))
                .andExpect(jsonPath("$.effectiveCommissionRate").value(8));
        send(patch("/api/admin/categories/" + l3), Map.of("clearCommissionRate", true))
                .andExpect(jsonPath("$.commissionRate").doesNotExist())
                .andExpect(jsonPath("$.effectiveCommissionRate").value(12.5));

        send(patch("/api/admin/categories/" + l3), Map.of("commissionRate", 150)).andExpect(status().isBadRequest());
    }

    @Test
    void hiddenCategoryLeavesThePublicTreeWithItsChildren() throws Exception {
        String l2 = create(rootId, unique("Theo mùa"), null);
        String l3 = create(l2, unique("Bánh trung thu"), null);
        send(patch("/api/admin/categories/" + l2), Map.of("active", false)).andExpect(status().isOk());

        String publicTree = mvc.perform(get("/api/categories")).andReturn().getResponse().getContentAsString();
        assertThat(publicTree).doesNotContain(l2).doesNotContain(l3);
        String adminTree = mvc.perform(auth(get("/api/admin/categories"), admin)).andReturn().getResponse().getContentAsString();
        assertThat(adminTree).contains(l2).contains(l3);
        assertThat(catalog.isAssignableLeaf(UUID.fromString(l3))).isFalse();
    }

    @Test
    void deleteOnlyUnusedLeaves() throws Exception {
        String l2 = create(rootId, unique("Thử xoá"), null);
        String l3 = create(l2, unique("Lá"), null);
        mvc.perform(auth(delete("/api/admin/categories/" + l2), admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_CHILDREN"));

        when(usage.dishCount(UUID.fromString(l3))).thenReturn(4L);
        mvc.perform(auth(delete("/api/admin/categories/" + l3), admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        when(usage.dishCount(UUID.fromString(l3))).thenReturn(0L);
        mvc.perform(auth(delete("/api/admin/categories/" + l3), admin)).andExpect(status().isNoContent());
        mvc.perform(auth(delete("/api/admin/categories/" + l2), admin)).andExpect(status().isNoContent());
    }

    @Test
    void categoryWithDishesGetsNoChildren() throws Exception {
        String l2 = create(rootId, unique("Có món"), null);
        when(usage.dishCount(UUID.fromString(l2))).thenReturn(1L);
        send(post("/api/admin/categories"), Map.of("parentId", l2, "name", "Con"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_HAS_DISHES"));
    }

    @Test
    void movesKeepTheDepth() throws Exception {
        String a = create(rootId, unique("Nhóm A"), null);
        String b = create(rootId, unique("Nhóm B"), null);
        String leaf = create(a, unique("Món"), null);
        send(patch("/api/admin/categories/" + leaf), Map.of("parentId", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(b));
        send(patch("/api/admin/categories/" + leaf), Map.of("parentId", rootId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_MOVE_INVALID"));
    }

    // --- helpers

    private String create(String parentId, String name, String rate) throws Exception {
        Map<String, Object> body = rate == null ? Map.of("parentId", parentId, "name", name)
                : Map.of("parentId", parentId, "name", name, "commissionRate", new BigDecimal(rate));
        String json = send(post("/api/admin/categories"), body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        return mvc.perform(auth(request, admin).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(Role role) {
        User user = new User(role.name().toLowerCase() + "-cat-" + System.nanoTime() + "@example.com", null, "Người dùng");
        user.addRole(role);
        user.markEmailVerified();
        return tokens.issue(users.saveAndFlush(user), role, Instant.now()).accessToken();
    }

    private static String unique(String name) {
        return name + " " + (System.nanoTime() % 1_000_000);
    }
}
