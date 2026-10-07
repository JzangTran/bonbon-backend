package com.bonbon.backend.settlement;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.settlement.service.CommissionRateService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Commission rates: the default, per-category rates that descendants inherit, and the history behind them (backend#83). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CommissionRateTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CategoryCatalog catalog;

    @Autowired
    CommissionRateService rates;

    @Autowired
    SystemSettingsService settings;

    String admin;
    UUID branch;
    UUID leaf;
    String originalDefault;

    @BeforeEach
    void setUp() {
        admin = token(Role.ADMIN);
        // A level-2 node that has a level-3 child, and nothing set on either of them.
        List<CategoryCatalog.RateNode> nodes = catalog.rateNodes();
        CategoryCatalog.RateNode child = nodes.stream().filter(n -> n.level() == 3).findFirst().orElseThrow();
        leaf = child.id();
        branch = child.parentId();
        jdbc.sql("update categories set commission_rate = null where id in (:ids)").param("ids", List.of(leaf, branch)).update();
        originalDefault = settings.getString("commission.default_rate", "10");
    }

    @AfterEach
    void restore() {
        // Other test classes expect the 10 % default and no rates on categories.
        settings.set("commission.default_rate", "10", ActorType.SYSTEM, null);
        settings.set("commission.vat_percent", "8", ActorType.SYSTEM, null);
        jdbc.sql("update categories set commission_rate = null where id in (:ids)").param("ids", List.of(leaf, branch)).update();
    }

    @Test
    void theScreenShowsTheDefaultAndWhereEachRateComesFrom() throws Exception {
        String body = call(get("/api/admin/commission-rates"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultRate").value(10)).andExpect(jsonPath("$.maxRate").value(30)).andExpect(jsonPath("$.vatPercent").value(8))
                .andReturn().getResponse().getContentAsString();
        assertThat(source(body, leaf)).isEqualTo("DEFAULT");

        call(put("/api/admin/categories/" + branch + "/commission-rate"), Map.of("ratePercent", 12.5)).andExpect(status().isOk());
        body = call(get("/api/admin/commission-rates"), null).andReturn().getResponse().getContentAsString();
        assertThat(source(body, branch)).isEqualTo("OWN");
        assertThat(source(body, leaf)).isEqualTo("ANCESTOR");
        call(get("/api/admin/commission-rates"), null)
                .andExpect(jsonPath("$.categories[?(@.id == '" + leaf + "')].effectiveRate").value(12.5))
                .andExpect(jsonPath("$.categories[?(@.id == '" + leaf + "')].sourceName").isNotEmpty());

        call(put("/api/admin/categories/" + leaf + "/commission-rate"), Map.of("ratePercent", 8)).andExpect(status().isOk());
        assertThat(catalog.effectiveCommissionRate(leaf)).hasValueSatisfying(r -> assertThat(r.doubleValue()).isEqualTo(8.0));

        call(delete("/api/admin/categories/" + leaf + "/commission-rate"), null).andExpect(status().isOk());
        assertThat(catalog.effectiveCommissionRate(leaf)).hasValueSatisfying(r -> assertThat(r.doubleValue()).isEqualTo(12.5));
        call(delete("/api/admin/categories/" + branch + "/commission-rate"), null).andExpect(status().isOk());
        assertThat(catalog.effectiveCommissionRate(leaf)).isEmpty();
    }

    @Test
    void everyChangeAppendsToTheHistoryAndNothingIsRewritten() throws Exception {
        call(put("/api/admin/categories/" + branch + "/commission-rate"), Map.of("ratePercent", 9)).andExpect(status().isOk());
        call(put("/api/admin/categories/" + branch + "/commission-rate"), Map.of("ratePercent", 9)).andExpect(status().isOk());
        call(put("/api/admin/categories/" + branch + "/commission-rate"), Map.of("ratePercent", 11.25)).andExpect(status().isOk());
        // The category screen records into the same history.
        call(patch("/api/admin/categories/" + branch), Map.of("clearCommissionRate", true)).andExpect(status().isOk());

        call(get("/api/admin/commission-rates/history?categoryId=" + branch), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].rate").doesNotExist()).andExpect(jsonPath("$.items[0].previousRate").value(11.25))
                .andExpect(jsonPath("$.items[1].rate").value(11.25)).andExpect(jsonPath("$.items[1].previousRate").value(9))
                .andExpect(jsonPath("$.items[2].rate").value(9)).andExpect(jsonPath("$.items[2].previousRate").doesNotExist())
                .andExpect(jsonPath("$.items[2].scope").value("CATEGORY")).andExpect(jsonPath("$.items[2].actedByType").value("ADMIN"))
                .andExpect(jsonPath("$.items[2].categoryName").isNotEmpty());
    }

    @Test
    void theDefaultChangesWithHistoryAndOnlyWhenItReallyChanges() throws Exception {
        long before = jdbc.sql("select count(*) from commission_rate_history where scope = 'DEFAULT'").query(Long.class).single();
        call(put("/api/admin/settings/commission-rate"), Map.of("ratePercent", 12)).andExpect(status().isOk()).andExpect(jsonPath("$.defaultRate").value(12));
        call(put("/api/admin/settings/commission-rate"), Map.of("ratePercent", 12)).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from commission_rate_history where scope = 'DEFAULT'").query(Long.class).single()).isEqualTo(before + 1);
        assertThat(rates.defaultRate().doubleValue()).isEqualTo(12.0);

        call(get("/api/admin/commission-rates/history?scope=DEFAULT&size=1"), null).andExpect(jsonPath("$.items[0].scope").value("DEFAULT"))
                .andExpect(jsonPath("$.items[0].rate").value(12)).andExpect(jsonPath("$.items[0].previousRate").value(Double.parseDouble(originalDefault)))
                .andExpect(jsonPath("$.items[0].categoryId").doesNotExist());
    }

    @Test
    void ratesOutsideTheAllowedRangeAreRefused() throws Exception {
        for (Object bad : new Object[] {31, -1, 10.555, "abc"}) {
            call(put("/api/admin/settings/commission-rate"), Map.of("ratePercent", bad)).andExpect(status().is4xxClientError());
            call(put("/api/admin/categories/" + branch + "/commission-rate"), Map.of("ratePercent", bad)).andExpect(status().is4xxClientError());
        }
        call(put("/api/admin/settings/commission-rate"), Map.of("ratePercent", 0)).andExpect(status().isOk());
        call(put("/api/admin/settings/commission-rate"), Map.of("ratePercent", 30)).andExpect(status().isOk());
        call(put("/api/admin/settings/commission-rate"), Map.of()).andExpect(status().isBadRequest());
        call(patch("/api/admin/categories/" + leaf), Map.of("commissionRate", 31)).andExpect(status().isBadRequest());
        call(put("/api/admin/categories/" + UUID.randomUUID() + "/commission-rate"), Map.of("ratePercent", 5)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        call(get("/api/admin/commission-rates/history?scope=NOPE"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SCOPE"));
        call(get("/api/admin/commission-rates/history?size=500"), null).andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheAdministratorCanReadOrChangeRates() throws Exception {
        for (Role role : new Role[] {Role.SELLER, Role.CUSTOMER}) {
            String other = token(role);
            mvc.perform(get("/api/admin/commission-rates").header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/settings/commission-rate").header("Authorization", "Bearer " + other).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ratePercent\":5}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/commission-rates")).andExpect(status().isUnauthorized());
        assertThat(rates.defaultRate().doubleValue()).isEqualTo(Double.parseDouble(originalDefault));
    }

    @Test
    void theVatPartIsSplitOutOfTheCommissionAndAlwaysAddsUp() {
        CommissionRateService.Split split = rates.split(10_800);
        assertThat(split.net()).isEqualTo(10_000);
        assertThat(split.vat()).isEqualTo(800);
        for (int gross : new int[] {0, 1, 7, 999, 7_000, 12_345, 1_000_001}) {
            CommissionRateService.Split s = rates.split(gross);
            assertThat(s.net() + s.vat()).isEqualTo(gross);
            assertThat(s.vat()).isBetween(0, gross);
        }
        // The VAT rate is a setting, not a constant.
        settings.set("commission.vat_percent", "10", ActorType.SYSTEM, null);
        assertThat(rates.split(11_000).net()).isEqualTo(10_000);
    }

    // --- helpers

    private static String source(String json, UUID id) {
        return com.jayway.jsonpath.JsonPath.<List<String>>read(json, "$.categories[?(@.id == '" + id + "')].source").get(0);
    }

    private String token(Role role) {
        User u = new User("commission-" + System.nanoTime() + "@example.com", null, "Người dùng");
        u.addRole(role);
        u.markEmailVerified();
        return tokens.issue(users.saveAndFlush(u), role, Instant.now()).accessToken();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + admin);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
