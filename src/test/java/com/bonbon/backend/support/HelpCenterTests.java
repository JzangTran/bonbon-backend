package com.bonbon.backend.support;

import java.time.Instant;
import java.util.List;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The help centre: who sees which article, accent-insensitive search, drafts, and the administrators' editor (backend#119). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class HelpCenterTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    String admin;
    String customer;
    String seller;
    String tag;

    @BeforeEach
    void setUp() {
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        seller = tokenOf(newUser("seller", Role.SELLER), Role.SELLER);
        tag = "zq" + Long.toString(System.nanoTime(), 36);
    }

    @Test
    void theStartingArticlesAreReadableWithoutLoggingIn() throws Exception {
        call(null, get("/api/help-articles"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4)))
                .andExpect(jsonPath("$.items[?(@.audience=='SELLER')]").isEmpty());
        call(null, get("/api/help-articles").param("q", "theo doi don"), null).andExpect(jsonPath("$.items[0].title").value("Theo dõi đơn hàng của tôi"));
    }

    @Test
    void anArticleIsOnlyShownToItsAudienceAndOnlyWhenPublished() throws Exception {
        String forSellers = create(Map.of("title", "Bí quyết " + tag, "body", "Chỉ cho người bán", "audience", "SELLER", "status", "PUBLISHED"));
        String forCustomers = create(Map.of("title", "Mẹo " + tag, "body", "Chỉ cho khách", "audience", "CUSTOMER", "status", "PUBLISHED"));
        String forAll = create(Map.of("title", "Chung " + tag, "body", "Cho mọi người", "audience", "ALL", "status", "PUBLISHED"));
        String draft = create(Map.of("title", "Nháp " + tag, "body", "Chưa xong", "audience", "ALL"));

        call(customer, get("/api/help-articles").param("q", tag), null).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.id=='" + forCustomers + "')]").isNotEmpty()).andExpect(jsonPath("$.items[?(@.id=='" + forAll + "')]").isNotEmpty());
        call(seller, get("/api/help-articles").param("q", tag), null).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.id=='" + forSellers + "')]").isNotEmpty());
        // A guest is treated like a customer.
        call(null, get("/api/help-articles").param("q", tag), null).andExpect(jsonPath("$.items[?(@.id=='" + forCustomers + "')]").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.id=='" + forSellers + "')]").isEmpty());

        call(customer, get("/api/help-articles/" + forSellers), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        call(seller, get("/api/help-articles/" + forSellers), null).andExpect(status().isOk()).andExpect(jsonPath("$.body").value("Chỉ cho người bán"));
        call(null, get("/api/help-articles/" + draft), null).andExpect(status().isNotFound());
        call(admin, get("/api/admin/help-articles/" + draft), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void searchIgnoresCaseAndAccentsAndLooksAtTitleBodyAndKeywords() throws Exception {
        String id = create(Map.of("title", "Hoàn tiền " + tag, "body", "Nội dung về thanh toán", "audience", "ALL", "status", "PUBLISHED", "keywords", List.of("Ví Điện Tử", " momo ")));
        call(null, get("/api/help-articles").param("q", "HOAN TIEN " + tag), null).andExpect(jsonPath("$.items[0].id").value(id));
        call(null, get("/api/help-articles").param("q", "thanh toan"), null).andExpect(jsonPath("$.items[?(@.id=='" + id + "')]").isNotEmpty());
        call(null, get("/api/help-articles").param("q", "vi dien tu"), null).andExpect(jsonPath("$.items[?(@.id=='" + id + "')]").isNotEmpty());
        call(null, get("/api/help-articles").param("q", "kh%ng c%"), null).andExpect(jsonPath("$.items.length()").value(0));
        call(admin, get("/api/admin/help-articles/" + id), null).andExpect(jsonPath("$.keywords[0]").value("momo")).andExpect(jsonPath("$.keywords[1]").value("ví điện tử"));
    }

    @Test
    void anAdministratorEditsPublishesAndDeletes() throws Exception {
        String id = create(Map.of("title", "Bài " + tag, "body", "Bản đầu", "audience", "CUSTOMER", "keywords", List.of("a", "A", "b")));
        call(admin, get("/api/admin/help-articles/" + id), null).andExpect(jsonPath("$.keywords.length()").value(2)).andExpect(jsonPath("$.status").value("DRAFT"));

        call(admin, patch("/api/admin/help-articles/" + id), Map.of("status", "PUBLISHED", "body", "Bản sửa")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.body").value("Bản sửa")).andExpect(jsonPath("$.title").value("Bài " + tag))
                .andExpect(jsonPath("$.keywords.length()").value(2));
        call(customer, get("/api/help-articles/" + id), null).andExpect(status().isOk());

        call(admin, patch("/api/admin/help-articles/" + id), Map.of("keywords", List.of("c"))).andExpect(jsonPath("$.keywords.length()").value(1)).andExpect(jsonPath("$.keywords[0]").value("c"));
        call(admin, patch("/api/admin/help-articles/" + id), Map.of("status", "DRAFT")).andExpect(status().isOk());
        call(customer, get("/api/help-articles/" + id), null).andExpect(status().isNotFound());

        call(admin, get("/api/admin/help-articles").param("status", "DRAFT").param("q", tag), null).andExpect(jsonPath("$.total").value(1));
        call(admin, get("/api/admin/help-articles").param("status", "BOGUS"), null).andExpect(status().isBadRequest());
        call(admin, delete("/api/admin/help-articles/" + id), null).andExpect(status().isNoContent());
        call(admin, get("/api/admin/help-articles/" + id), null).andExpect(status().isNotFound());
        call(admin, delete("/api/admin/help-articles/" + id), null).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select count(*) from help_article_keywords where article_id = :id").param("id", UUID.fromString(id)).query(Long.class).single()).isZero();
    }

    @Test
    void theEditorValidatesAndIsForAdministratorsOnly() throws Exception {
        call(admin, post("/api/admin/help-articles"), Map.of("title", "", "body", "x", "audience", "ALL")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/help-articles"), Map.of("title", "x", "body", "x", "audience", "EVERYONE")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/help-articles"), Map.of("title", "x", "body", "x", "audience", "ALL", "status", "LIVE")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/help-articles"), Map.of("title", "x", "body", "x", "audience", "ALL", "keywords", List.of("k".repeat(51)))).andExpect(status().isBadRequest());
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/help-articles"), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/help-articles"), Map.of("title", "x", "body", "x", "audience", "ALL")).andExpect(status().isForbidden());
        }
        call(null, get("/api/admin/help-articles"), null).andExpect(status().isUnauthorized());
    }

    // --- helpers

    private String create(Map<String, Object> body) throws Exception {
        return JsonPath.read(call(admin, post("/api/admin/help-articles"), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private UUID newUser(String kind, Role role) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", null, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
