package com.bonbon.backend.marketplace.merchant;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Selling-day helpers from the UX benchmark: a dish sold out "for today" comes back by itself, a timed pause ends by itself, and the
 * shop's quick replies for chat (three starters, unique shortcuts).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SellingDayTests {

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
    com.bonbon.backend.marketplace.merchant.service.SellingDayJob job;

    String seller;
    UUID vendorId;
    String dishId;

    @BeforeEach
    void setUp() throws Exception {
        User user = new User("selling-day-" + System.nanoTime() + "@example.com", null, "Chủ quán");
        user.addRole(Role.SELLER);
        user.markEmailVerified();
        UUID owner = users.saveAndFlush(user).getId();
        seller = tokens.issue(users.findById(owner).orElseThrow(), Role.SELLER, Instant.now()).accessToken();
        vendorId = jdbc.sql("insert into marketplace.vendors (owner_user_id, status, name, decided_at) values (:o, 'APPROVED', 'Quán Ngày Bán', now()) returning id")
                .param("o", owner).query(UUID.class).single();
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        String section = JsonPath.read(call(post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(),
                "$.sections[0].id");
        dishId = JsonPath.read(call(post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm sườn", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
    }

    @Test
    void aDishSoldOutForTodayComesBackByItself() throws Exception {
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "SOLD_OUT", "until", "TODAY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].items[0].soldOut").value(true))
                .andExpect(jsonPath("$.sections[0].items[0].soldOutUntil").exists());
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "SOLD_OUT", "until", "WEEK")).andExpect(status().isBadRequest());

        // the time is up: the dish counts as on sale at once, and the job stores it
        jdbc.sql("update marketplace.menu_items set sold_out_until = now() - interval '1 minute' where id = cast(:i as uuid)").param("i", dishId).update();
        call(get("/api/merchant/menu"), null).andExpect(jsonPath("$.sections[0].items[0].soldOut").value(false));
        job.run();
        assertThat(jdbc.sql("select status from marketplace.menu_items where id = cast(:i as uuid)").param("i", dishId).query(String.class).single())
                .isEqualTo("AVAILABLE");

        // sold out with no end stays sold out
        call(patch("/api/merchant/menu-items/" + dishId + "/status"), Map.of("status", "SOLD_OUT"))
                .andExpect(jsonPath("$.sections[0].items[0].soldOutUntil").doesNotExist());
        job.run();
        call(get("/api/merchant/menu"), null).andExpect(jsonPath("$.sections[0].items[0].soldOut").value(true));
    }

    @Test
    void aTimedPauseEndsByItself() throws Exception {
        call(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false, "pauseMinutes", 30, "reason", "Quá đông"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.pausedUntil").exists())
                .andExpect(jsonPath("$.pauseReason").value("Quá đông"));
        call(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false, "pauseMinutes", 5)).andExpect(status().isBadRequest());

        jdbc.sql("update marketplace.vendors set paused_until = now() - interval '1 minute' where id = :v").param("v", vendorId).update();
        job.run();
        call(get("/api/merchant/shop"), null)
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.pausedUntil").doesNotExist());

        // a pause with no end is never lifted by the job
        call(put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false)).andExpect(jsonPath("$.pausedUntil").doesNotExist());
        job.run();
        call(get("/api/merchant/shop"), null).andExpect(jsonPath("$.acceptingOrders").value(false));
    }

    @Test
    void quickRepliesStartWithThreeAndKeepShortcutsUnique() throws Exception {
        call(get("/api/merchant/quick-replies"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        String id = JsonPath.read(call(post("/api/merchant/quick-replies"), Map.of("shortcut", "dia-chi", "text", "Bạn gửi giúp quán số phòng nhé."))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(post("/api/merchant/quick-replies"), Map.of("shortcut", "dia-chi", "text", "Trùng")).andExpect(jsonPath("$.code").value("QUICK_REPLY_SHORTCUT_TAKEN"));
        call(post("/api/merchant/quick-replies"), Map.of("shortcut", "Có Dấu", "text", "x")).andExpect(status().isBadRequest());
        call(put("/api/merchant/quick-replies/" + id), Map.of("shortcut", "phong", "text", "Bạn gửi số phòng giúp quán.")).andExpect(jsonPath("$.shortcut").value("phong"));
        call(delete("/api/merchant/quick-replies/" + id), null).andExpect(status().isNoContent());
        call(delete("/api/merchant/quick-replies/" + id), null).andExpect(status().isNotFound());

        // deleting every starter does not bring them back
        String list = call(get("/api/merchant/quick-replies"), null).andReturn().getResponse().getContentAsString();
        for (Object starter : (java.util.List<?>) JsonPath.read(list, "$[*].id")) {
            call(delete("/api/merchant/quick-replies/" + starter), null).andExpect(status().isNoContent());
        }
        call(get("/api/merchant/quick-replies"), null).andExpect(jsonPath("$.length()").value(0));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + seller);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
