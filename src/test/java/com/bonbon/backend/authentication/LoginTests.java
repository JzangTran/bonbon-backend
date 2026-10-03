package com.bonbon.backend.authentication;

import java.util.concurrent.atomic.AtomicInteger;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.common.security.CaptchaVerifier;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LoginTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "matkhau123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    JwtDecoder decoder;

    /** Real CAPTCHA is off in tests; this stand-in accepts only the token "solved". */
    @MockitoBean
    CaptchaVerifier captcha;

    String ip;

    @BeforeEach
    void setUp() {
        when(captcha.isValid(any(), any())).thenReturn(false);
        when(captcha.isValid(eq("solved"), any())).thenReturn(true);
        ip = "10.20." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
    }

    @Test
    void singleRoleCustomerGetsTokensDirectly() throws Exception {
        String email = user("an", true, Role.CUSTOMER);
        login(email, PASSWORD, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRoleSelection").value(false))
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.activeRole").value("CUSTOMER"))
                .andExpect(jsonPath("$.user.availableRoles[0]").value("CUSTOMER"));
    }

    @Test
    void unknownEmailAndWrongPasswordLookTheSame() throws Exception {
        String email = user("binh", true, Role.CUSTOMER);
        String wrong = login(email, "sai-mat-khau", null).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknown = login("khong-co-" + System.nanoTime() + "@example.com", PASSWORD, null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(unknown);
        assertThat((String) JsonPath.read(wrong, "$.code")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void unverifiedEmailIsBlockedAfterACorrectPassword() throws Exception {
        String email = user("chi", false, Role.CUSTOMER);
        login(email, PASSWORD, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void threeFailuresRequireCaptchaThenSolvingItLetsTheOwnerIn() throws Exception {
        String email = user("dung", true, Role.SELLER);
        for (int i = 0; i < 3; i++) {
            login(email, "sai-" + i, null).andExpect(status().isUnauthorized());
        }
        login(email, PASSWORD, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"));
        login(email, PASSWORD, "solved").andExpect(status().isOk())
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"));
        // success clears the counters: no captcha on the next attempt
        login(email, PASSWORD, null).andExpect(status().isOk());
    }

    @Test
    void dualRoleIdentityChoosesARoleWithASingleUseToken() throws Exception {
        String email = user("em", true, Role.CUSTOMER, Role.SELLER);
        String body = login(email, PASSWORD, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRoleSelection").value(true))
                .andExpect(jsonPath("$.tokens").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String roleToken = JsonPath.read(body, "$.roleToken");

        mvc.perform(json(post("/api/auth/select-role"), "{\"role\":\"SELLER\",\"roleToken\":\"" + roleToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"))
                .andExpect(jsonPath("$.user.availableRoles.length()").value(2));

        mvc.perform(json(post("/api/auth/select-role"), "{\"role\":\"SELLER\",\"roleToken\":\"" + roleToken + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE_TOKEN"));
    }

    @Test
    void switchRoleIssuesTokensForTheOtherRoleKeepingAuthTime() throws Exception {
        String email = user("giang", true, Role.CUSTOMER, Role.SELLER);
        String roleToken = JsonPath.read(login(email, PASSWORD, null).andReturn().getResponse().getContentAsString(), "$.roleToken");
        String customerAccess = JsonPath.read(mvc.perform(json(post("/api/auth/select-role"),
                "{\"role\":\"CUSTOMER\",\"roleToken\":\"" + roleToken + "\"}")).andReturn().getResponse().getContentAsString(),
                "$.tokens.accessToken");

        String body = mvc.perform(json(post("/api/auth/switch-role"), "{\"role\":\"SELLER\"}")
                        .header("Authorization", "Bearer " + customerAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"))
                .andReturn().getResponse().getContentAsString();
        String sellerAccess = JsonPath.read(body, "$.tokens.accessToken");

        Object before = decoder.decode(customerAccess).getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        Object after = decoder.decode(sellerAccess).getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        assertThat(after).isEqualTo(before);
        assertThat(decoder.decode(sellerAccess).getClaimAsStringList(CurrentPrincipal.CLAIM_PERMISSIONS)).contains("order:write");
    }

    @Test
    void switchingToARoleNotHeldIsRefused() throws Exception {
        String email = user("hoa", true, Role.CUSTOMER);
        String access = JsonPath.read(login(email, PASSWORD, null).andReturn().getResponse().getContentAsString(), "$.tokens.accessToken");
        mvc.perform(json(post("/api/auth/switch-role"), "{\"role\":\"SELLER\"}").header("Authorization", "Bearer " + access))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_HELD"));
        mvc.perform(json(post("/api/auth/switch-role"), "{\"role\":\"SELLER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotSignInThroughTheAppLogin() throws Exception {
        String email = user("quantri", true, Role.ADMIN);
        login(email, PASSWORD, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    private ResultActions login(String email, String password, String captchaToken) throws Exception {
        String captchaJson = captchaToken == null ? "" : ",\"captchaToken\":\"" + captchaToken + "\"";
        return mvc.perform(json(post("/api/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"" + captchaJson + "}"));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        String clientIp = ip;
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr(clientIp); return r; });
    }

    private String user(String prefix, boolean verified, Role... roles) {
        User user = new User(prefix + "-" + System.nanoTime() + "@example.com", passwords.encode(PASSWORD), prefix);
        for (Role role : roles) {
            user.addRole(role);
        }
        if (verified) {
            user.markEmailVerified();
        }
        return users.saveAndFlush(user).getEmail();
    }
}
