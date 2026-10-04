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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Option groups, attaching them to dishes, and the sold-out switches; approval is simulated in the database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OptionTests {

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

    String seller;
    UUID sellerId;
    String dishId;

    @BeforeEach
    void setUp() throws Exception {
        User u = new User("opt-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        sellerId = u.getId();
        seller = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(put("/api/merchant/shop/steps/1"), Map.of("name", "Trà Sữa", "phone", "0912345678",
                "email", "tra@example.com", "placeId", place)).andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o")
                .param("o", sellerId).update();
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1")
                .query(UUID.class).single().toString();
        String section = JsonPath.read(call(post("/api/merchant/menu-sections"), Map.of("name", "Trà"))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf,
                "name", "Trà sữa", "price", 30000)).andReturn().getResponse().getContentAsString(),
                "$.sections[0].items[0].id");
    }

    @Test
    void aGroupIsCreatedWithOptionsAndListed() throws Exception {
        call(post("/api/merchant/option-groups"), size()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.groups[0].name").value("Size"))
                .andExpect(jsonPath("$.groups[0].min").value(1))
                .andExpect(jsonPath("$.groups[0].options.length()").value(2))
                .andExpect(jsonPath("$.groups[0].options[1].priceDelta").value(5000))
                .andExpect(jsonPath("$.groups[0].options[0].defaultChoice").value(true));
        call(get("/api/merchant/option-groups"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1));
    }

    @Test
    void groupRulesAreEnforced() throws Exception {
        call(post("/api/merchant/option-groups"), group("Size", 2, 1, List.of(opt("M", 0), opt("L", 1000))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_GROUP_INVALID"));
        call(post("/api/merchant/option-groups"), group("Size", 1, 3, List.of(opt("M", 0), opt("L", 1000))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_GROUP_INVALID"));
        call(post("/api/merchant/option-groups"), group("Size", 1, 1,
                List.of(Map.of("name", "M", "priceDelta", 0, "defaultChoice", true),
                        Map.of("name", "L", "priceDelta", 1000, "defaultChoice", true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OPTION_GROUP_INVALID"));
        call(post("/api/merchant/option-groups"), group("Size", 0, 1, List.of(opt("M", -5)))).andExpect(status().isBadRequest());
    }

    @Test
    void updatingReplacesOptionsAndArchivesTheOnesLeftOut() throws Exception {
        String body = call(post("/api/merchant/option-groups"), size()).andReturn().getResponse().getContentAsString();
        String group = JsonPath.read(body, "$.groups[0].id");
        String medium = JsonPath.read(body, "$.groups[0].options[0].id");

        call(put("/api/merchant/option-groups/" + group), group("Cỡ ly", 0, 2,
                List.of(Map.of("id", medium, "name", "Vừa", "priceDelta", 1000), opt("Lớn", 8000), opt("Siêu lớn", 12000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].name").value("Cỡ ly"))
                .andExpect(jsonPath("$.groups[0].options.length()").value(3))
                .andExpect(jsonPath("$.groups[0].options[0].name").value("Vừa"));

        call(put("/api/merchant/option-groups/" + group), group("Cỡ ly", 0, 1,
                List.of(Map.of("id", medium, "name", "Vừa", "priceDelta", 1000)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].options.length()").value(1));
        call(put("/api/merchant/option-groups/" + group), group("Cỡ ly", 0, 1,
                List.of(Map.of("id", UUID.randomUUID().toString(), "name", "X", "priceDelta", 0))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OPTION_NOT_FOUND"));
    }

    @Test
    void groupsAttachToDishesInOrderAndDetachWhenDeleted() throws Exception {
        String size = groupId(call(post("/api/merchant/option-groups"), size()), "Size");
        String topping = groupId(call(post("/api/merchant/option-groups"),
                group("Topping", 0, 2, List.of(opt("Trân châu", 7000), opt("Pudding", 6000)))), "Topping");

        call(put("/api/merchant/menu-items/" + dishId + "/option-groups"), Map.of("groupIds", List.of(topping, size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].optionGroupIds[0]").value(topping))
                .andExpect(jsonPath("$.sections[0].items[0].optionGroupIds[1]").value(size));
        call(get("/api/merchant/option-groups"), null)
                .andExpect(jsonPath("$.groups[?(@.name=='Size')].menuItemIds[0]").value(dishId));

        call(put("/api/merchant/menu-items/" + dishId + "/option-groups"), Map.of("groupIds", List.of(size, size)))
                .andExpect(status().isBadRequest());
        call(put("/api/merchant/menu-items/" + dishId + "/option-groups"),
                Map.of("groupIds", List.of(UUID.randomUUID().toString()))).andExpect(status().isNotFound());

        call(delete("/api/merchant/option-groups/" + size), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups.length()").value(1));
        call(get("/api/merchant/menu"), null)
                .andExpect(jsonPath("$.sections[0].items[0].optionGroupIds.length()").value(1));
    }

    @Test
    void aDishIsSoldOutWhenARequiredGroupHasTooFewAvailableOptions() throws Exception {
        String body = call(post("/api/merchant/option-groups"), size()).andReturn().getResponse().getContentAsString();
        String size = JsonPath.read(body, "$.groups[0].id");
        String medium = JsonPath.read(body, "$.groups[0].options[0].id");
        String large = JsonPath.read(body, "$.groups[0].options[1].id");
        call(put("/api/merchant/menu-items/" + dishId + "/option-groups"), Map.of("groupIds", List.of(size)))
                .andExpect(jsonPath("$.sections[0].items[0].soldOut").value(false));

        call(patch("/api/merchant/options/" + medium + "/status"), Map.of("status", "SOLD_OUT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].options[0].status").value("SOLD_OUT"));
        call(get("/api/merchant/menu"), null).andExpect(jsonPath("$.sections[0].items[0].soldOut").value(false));

        call(patch("/api/merchant/options/" + large + "/status"), Map.of("status", "SOLD_OUT")).andExpect(status().isOk());
        call(get("/api/merchant/menu"), null).andExpect(jsonPath("$.sections[0].items[0].soldOut").value(true));

        call(patch("/api/merchant/options/" + large + "/status"), Map.of("status", "ARCHIVED"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theQuickSwitchMarksADishSoldOutWithoutTouchingStock() throws Exception {
        call(patch("/api/merchant/menu-items/" + dishId), Map.of("stockQuantity", 5)).andExpect(status().isOk());
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "SOLD_OUT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].status").value("SOLD_OUT"))
                .andExpect(jsonPath("$.sections[0].items[0].soldOut").value(true))
                .andExpect(jsonPath("$.sections[0].items[0].stockQuantity").value(5));
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "AVAILABLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].soldOut").value(false))
                .andExpect(jsonPath("$.sections[0].items[0].stockQuantity").value(5));
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "BOGUS")).andExpect(status().isBadRequest());
    }

    @Test
    void anotherShopCannotUseTheseGroups() throws Exception {
        String size = groupId(call(post("/api/merchant/option-groups"), size()), "Size");
        User u = new User("opt2-" + System.nanoTime() + "@example.com", null, "Chủ quán 2");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        u = users.saveAndFlush(u);
        String other = tokens.issue(u, Role.SELLER, Instant.now()).accessToken();
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        mvc.perform(put("/api/merchant/shop/steps/1").header("Authorization", "Bearer " + other)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("name", "Quán 2",
                        "phone", "0912345678", "email", "q2@example.com", "placeId", place)))).andExpect(status().isOk());
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o")
                .param("o", u.getId()).update();
        mvc.perform(delete("/api/merchant/option-groups/" + size).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
    }

    // --- helpers

    private Map<String, Object> size() {
        return group("Size", 1, 1, List.of(Map.of("name", "M", "priceDelta", 0, "defaultChoice", true), opt("L", 5000)));
    }

    private static Map<String, Object> group(String name, int min, int max, List<Map<String, Object>> options) {
        return Map.of("name", name, "min", min, "max", max, "options", options);
    }

    private static Map<String, Object> opt(String name, int priceDelta) {
        return Map.of("name", name, "priceDelta", priceDelta);
    }

    private static String groupId(ResultActions result, String name) throws Exception {
        List<String> ids = JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.groups[?(@.name=='" + name + "')].id");
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
