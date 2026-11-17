package com.bonbon.backend.identity.developer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.mail.EmailSender;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The developer portal: the API reference is closed to everyone but approved developers; a request goes to an administrator, who
 * approves (new account with a set-password link, or the role added to an existing account), rejects with a reason, or revokes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "bonbon.api-docs.public=false")
@Import(TestcontainersConfiguration.class)
class DeveloperPortalTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "Ma-Nguon-2027";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    EmailSender mail;

    String admin;
    String tag;

    @Autowired
    com.bonbon.backend.common.ratelimit.RateLimiter limiter;

    @BeforeEach
    void setUp() {
        // three requests a day per address; every test here comes from 127.0.0.1
        limiter.reset("developer-request:127.0.0.1");
        tag = Long.toString(System.nanoTime(), 36);
        User a = new User("dev-admin-" + tag + "@example.com", null, "Quản Trị");
        a.addRole(Role.ADMIN);
        a.markEmailVerified();
        admin = tokens.issue(users.saveAndFlush(a), Role.ADMIN, Instant.now()).accessToken();
    }

    @Test
    void theReferenceOpensOnlyForAnApprovedDeveloper() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + admin)).andExpect(status().isForbidden());

        String email = "dev-" + tag + "@example.com";
        String id = JsonPath.read(ask(email).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        ask(email).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_PENDING"));
        call(admin, get("/api/admin/developer-access-requests"), null)
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].existingAccount").value(false));

        // before approval nobody can sign in with that address
        login(email).andExpect(status().isUnauthorized());

        call(admin, post("/api/admin/developer-access-requests/" + id + "/approve"), null).andExpect(status().isNoContent());
        call(admin, post("/api/admin/developer-access-requests/" + id + "/approve"), null).andExpect(jsonPath("$.code").value("ACCESS_REQUEST_DECIDED"));
        User dev = users.findByEmail(email).orElseThrow();
        assertThat(dev.getRoles()).containsExactly(Role.DEVELOPER);
        assertThat(dev.hasPassword()).isFalse(); // the set-password link comes by email

        dev.setPasswordHash(passwords.encode(PASSWORD));
        users.saveAndFlush(dev);
        String token = JsonPath.read(login(email).andExpect(status().isOk()).andExpect(jsonPath("$.user.activeRole").value("DEVELOPER"))
                .andReturn().getResponse().getContentAsString(), "$.tokens.accessToken");
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/developer/access-requests']").exists());
        // the apps' sign-in does not take a developer-only account
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        // and a developer token opens nothing on the selling or admin side
        call(token, get("/api/merchant/orders"), null).andExpect(status().isForbidden());
        call(token, get("/api/admin/developer-access-requests"), null).andExpect(status().isForbidden());

        // revoking ends the access at once
        call(admin, post("/api/admin/developer-access-requests/" + id + "/revoke"), Map.of("reason", "Dùng API sai mục đích")).andExpect(status().isNoContent());
        mvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        login(email).andExpect(status().isUnauthorized());
    }

    @Test
    void anExistingSellerKeepsItsSignInAndARejectionCarriesAReason() throws Exception {
        String email = "seller-dev-" + tag + "@example.com";
        User seller = new User(email, passwords.encode(PASSWORD), "Chủ Quán");
        seller.addRole(Role.SELLER);
        seller.markEmailVerified();
        users.saveAndFlush(seller);

        String id = JsonPath.read(ask(email).andReturn().getResponse().getContentAsString(), "$.id");
        call(admin, get("/api/admin/developer-access-requests"), null)
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].existingAccount").value(true));
        call(admin, post("/api/admin/developer-access-requests/" + id + "/approve"), null).andExpect(status().isNoContent());
        assertThat(users.findByEmail(email).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.SELLER, Role.DEVELOPER);
        // the seller site still signs straight in as a seller, without a role choice and without the developer role
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.activeRole").value("SELLER"))
                .andExpect(jsonPath("$.user.availableRoles.length()").value(1));
        login(email).andExpect(status().isOk());

        String other = JsonPath.read(ask("other-" + tag + "@example.com").andReturn().getResponse().getContentAsString(), "$.id");
        call(admin, post("/api/admin/developer-access-requests/" + other + "/reject"), Map.of("reason", "")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/developer-access-requests/" + other + "/reject"), Map.of("reason", "Thiếu mô tả ứng dụng")).andExpect(status().isNoContent());
        call(admin, get("/api/admin/developer-access-requests?status=REJECTED"), null)
                .andExpect(jsonPath("$.items[?(@.id == '" + other + "')].reason").value("Thiếu mô tả ứng dụng"));
        call(seller(email), get("/api/admin/developer-access-requests"), null).andExpect(status().isForbidden());

        // short purpose, bad website
        mvc.perform(post("/api/developer/access-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "Lan Anh", "email", "x-" + tag + "@example.com", "website", "http://a.vn", "purpose", "ngắn"))))
                .andExpect(status().isBadRequest());
    }

    private String seller(String email) {
        return tokens.issue(users.findByEmail(email).orElseThrow(), Role.SELLER, Instant.now()).accessToken();
    }

    private ResultActions ask(String email) throws Exception {
        return mvc.perform(post("/api/developer/access-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("name", "Lan Anh", "email", email, "company", "Công ty Mã Nguồn",
                        "purpose", "Tích hợp đặt món của bonbon vào ứng dụng nội bộ của toà nhà."))));
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/developer/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD))));
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
