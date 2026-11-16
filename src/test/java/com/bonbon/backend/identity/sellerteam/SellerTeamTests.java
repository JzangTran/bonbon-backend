package com.bonbon.backend.identity.sellerteam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Seller team (flows/seller-team): a shop account registers a main account and its shop is linked; another shop authorizes a link; the
 * main account creates a role and a member; the member works only the shop and the permissions given; shrinking access is immediate;
 * team sessions never reach the owner's personal endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SellerTeamTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private static final String PASSWORD = "Bep-Sen-2027";

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

    String tag;
    UUID ownerA;
    String shopA;
    UUID vendorA;
    String emailB;
    UUID ownerB;
    String shopB;
    UUID vendorB;

    @BeforeEach
    void setUp() {
        tag = Long.toString(System.nanoTime(), 36);
        ownerA = seller("a");
        shopA = tokenOf(ownerA);
        vendorA = shop(ownerA, "Quán A " + tag);
        ownerB = seller("b");
        emailB = users.findById(ownerB).orElseThrow().getEmail();
        shopB = tokenOf(ownerB);
        vendorB = shop(ownerB, "Quán B " + tag);
    }

    @Test
    void aShopRegistersAMainAccountWhichLinksASecondShopAndRunsAMember() throws Exception {
        String username = "chu" + tag;
        String email = "main-" + tag + "@example.com";
        // register: code to the address, the shop's own password, and the shop is linked at once
        call(shopA, post("/api/seller-team/main-accounts/code"), Map.of("email", email)).andExpect(status().isAccepted());
        Map<String, Object> register = new java.util.HashMap<>(Map.of("accountName", "Chuỗi Quán Sen", "email", email, "username", username,
                "password", PASSWORD, "confirmPassword", PASSWORD, "code", "000000", "currentPassword", "sai-mat-khau"));
        call(shopA, post("/api/seller-team/main-accounts"), register).andExpect(status().isUnauthorized());
        register.put("currentPassword", PASSWORD);
        call(shopA, post("/api/seller-team/main-accounts"), register).andExpect(jsonPath("$.code").value("CODE_INVALID"));
        register.put("code", lastCode());
        call(shopA, post("/api/seller-team/main-accounts"), register).andExpect(status().isCreated())
                .andExpect(jsonPath("$.login").value(username + ":main"));
        call(shopA, post("/api/seller-team/main-accounts/code"), Map.of("email", "x" + email)).andExpect(jsonPath("$.code").value("ALREADY_HAS_MAIN_ACCOUNT"));

        // sign in as the main account: the console, with shop A to open
        String main = login(username + ":main").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.team.kind").value("MAIN_ACCOUNT"))
                .andExpect(jsonPath("$.user.team.shopId").doesNotExist())
                .andExpect(jsonPath("$.user.team.shops[0].vendorId").value(vendorA.toString()))
                .andReturn().getResponse().getContentAsString();
        String console = JsonPath.read(main, "$.tokens.accessToken");
        call(console, get("/api/merchant/orders"), null).andExpect(status().isForbidden());
        call(console, get("/api/account/me"), null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TEAM_SESSION_NOT_ALLOWED"));

        // ask for shop B; B authorizes from its own fresh session
        call(console, post("/api/seller-team/links/code"), null).andExpect(status().isAccepted());
        String linkId = JsonPath.read(call(console, post("/api/seller-team/links"), Map.of("shopEmail", emailB, "code", lastCode()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_AUTHORIZATION"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(shopB, get("/api/merchant/link-requests"), null).andExpect(jsonPath("$[0].mainLogin").value(username + ":main"));
        call(shopB, post("/api/merchant/link-requests/" + linkId + "/authorize"), null).andExpect(status().isNoContent());
        call(console, get("/api/seller-team/me"), null).andExpect(jsonPath("$.links.length()").value(2));

        // open shop B as the main account: the seller side works, opening a new shop does not
        String asB = JsonPath.read(call(console, post("/api/seller-team/switch-shop"), Map.of("vendorId", vendorB.toString()))
                .andExpect(jsonPath("$.user.team.shopName").value("Quán B " + tag)).andReturn().getResponse().getContentAsString(), "$.accessToken");
        call(asB, get("/api/merchant/menu"), null).andExpect(status().isOk());
        call(asB, get("/api/merchant/orders"), null).andExpect(status().isOk());

        // a role that may only read orders, and a member on both shops
        String roleId = JsonPath.read(call(console, post("/api/seller-team/roles"), Map.of("name", "Thu ngân", "permissions", List.of("order:read")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(console, post("/api/seller-team/roles"), Map.of("name", "Lén", "permissions", List.of("team:manage")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        Map<String, Object> member = Map.of("memberName", "Lan Anh", "loginCode", "nhanvien01", "password", PASSWORD, "confirmPassword", PASSWORD,
                "roleId", roleId, "vendorIds", List.of(vendorA.toString(), vendorB.toString()));
        String memberId = JsonPath.read(call(console, post("/api/seller-team/members"), member).andExpect(status().isCreated())
                .andExpect(jsonPath("$.login").value(username + ":nhanvien01"))
                .andExpect(jsonPath("$.member.shops.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "$.member.id");
        call(console, post("/api/seller-team/members"), member).andExpect(jsonPath("$.code").value("LOGIN_CODE_TAKEN"));

        String staff = JsonPath.read(login(username + ":nhanvien01").andExpect(jsonPath("$.user.team.kind").value("MEMBER"))
                .andExpect(jsonPath("$.user.team.shops.length()").value(2)).andReturn().getResponse().getContentAsString(), "$.tokens.accessToken");
        call(staff, get("/api/merchant/orders"), null).andExpect(status().isOk());
        call(staff, get("/api/merchant/menu"), null).andExpect(status().isForbidden());
        call(staff, get("/api/seller-team/members"), null).andExpect(status().isForbidden());
        call(staff, post("/api/me/data-export"), null).andExpect(status().isForbidden());

        // taking shop B away: the member's current token stops, the refresh picks up the smaller set
        call(console, delete("/api/seller-team/members/" + memberId + "/shops/" + vendorB), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.shops.length()").value(1));
        call(staff, get("/api/merchant/orders"), null).andExpect(status().isUnauthorized());
        String refresh = JsonPath.read(login(username + ":nhanvien01").andReturn().getResponse().getContentAsString(), "$.tokens.refreshToken");
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.team.shops.length()").value(1));

        // disabling: signed out everywhere, and told why on the next sign-in
        call(console, patch("/api/seller-team/members/" + memberId), Map.of("status", "DISABLED")).andExpect(status().isOk());
        login(username + ":nhanvien01").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MEMBER_DISABLED"));

        // shop B ends the link: the main account's open token for B stops at once
        call(shopB, delete("/api/merchant/link-requests/" + linkId), null).andExpect(status().isNoContent());
        call(asB, get("/api/merchant/menu"), null).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongLoginsAllLookTheSame() throws Exception {
        login("khongco:main").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("khongco:nhanvien").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("khongco").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        call(shopA, get("/api/seller-team/members"), null).andExpect(status().isForbidden());
    }

    private ResultActions login(String login) throws Exception {
        return mvc.perform(post("/api/seller-team/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("login", login, "password", PASSWORD))));
    }

    private String lastCode() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(mail, atLeastOnce()).send(captor.capture());
        Matcher m = CODE.matcher(captor.getValue().subject());
        return m.find() ? m.group(1) : "";
    }

    private UUID seller(String kind) {
        User user = new User("team-" + kind + "-" + tag + "@example.com", passwords.encode(PASSWORD), "Chủ quán");
        user.addRole(Role.SELLER);
        user.markEmailVerified();
        return users.saveAndFlush(user).getId();
    }

    private UUID shop(UUID owner, String name) {
        return jdbc.sql("insert into marketplace.vendors (owner_user_id, status, name, decided_at) values (:o, 'APPROVED', :n, now()) returning id")
                .param("o", owner).param("n", name).query(UUID.class).single();
    }

    private String tokenOf(UUID userId) {
        return tokens.issue(users.findById(userId).orElseThrow(), Role.SELLER, Instant.now()).accessToken();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
