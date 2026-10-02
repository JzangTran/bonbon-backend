package com.bonbon.backend.adminauthentication;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.AdminAccountService;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdminAuthTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");
    private static final String PASSWORD = "matkhau123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    TokenService tokens;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    AdminAccountService admins;

    @MockitoBean
    EmailSender emailSender;

    String adminEmail;

    @BeforeEach
    void setUp() {
        clearInvocations(emailSender);
        adminEmail = user("admin", Role.ADMIN);
    }

    @Test
    void adminSignsInWithTheAdminPermissionBundle() throws Exception {
        String body = adminLogin(adminEmail, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        assertThat(decoder.decode(access).getClaimAsStringList(CurrentPrincipal.CLAIM_PERMISSIONS))
                .contains("admin:write", "merchant-approval:decide", "refund:process");
    }

    @Test
    void customerCannotUseTheAdminLogin() throws Exception {
        String customer = user("khach", Role.CUSTOMER);
        adminLogin(customer, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void creatingAnAdminNeedsAdminWrite() throws Exception {
        String customerAccess = tokens.issue(users.findByEmail(user("nguoimua", Role.CUSTOMER)).orElseThrow(),
                Role.CUSTOMER, Instant.now()).accessToken();
        createAdmin(customerAccess, "moi-" + System.nanoTime() + "@example.com").andExpect(status().isForbidden());
        createAdmin(null, "moi-" + System.nanoTime() + "@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    void newAdminSetsTheirOwnPasswordFromTheEmailedLinkThenSignsIn() throws Exception {
        String access = adminAccess();
        String newEmail = "admin2-" + System.nanoTime() + "@example.com";
        createAdmin(access, newEmail).andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty());

        User created = users.findByEmail(newEmail).orElseThrow();
        assertThat(created.hasPassword()).isFalse();
        assertThat(created.isEmailVerified()).isTrue();
        adminLogin(newEmail, PASSWORD).andExpect(status().isUnauthorized());

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, timeout(5000)).send(captor.capture());
        assertThat(captor.getValue().textBody()).contains("/set-password?token=");
        Matcher m = TOKEN.matcher(captor.getValue().textBody());
        assertThat(m.find()).isTrue();

        // a set-password link is not a reset link and vice versa
        mvc.perform(json(post("/api/auth/reset-password"), "{\"token\":\"" + m.group(1) + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/auth/set-initial-password"), "{\"token\":\"" + m.group(1) + "\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        adminLogin(newEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void emailIsUniqueAcrossRoles() throws Exception {
        String sellerEmail = user("nguoiban", Role.SELLER);
        createAdmin(adminAccess(), sellerEmail.toUpperCase()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void adminLogoutRevokesTheSession() throws Exception {
        String body = adminLogin(adminEmail, PASSWORD).andReturn().getResponse().getContentAsString();
        String access = JsonPath.read(body, "$.accessToken");
        String refresh = JsonPath.read(body, "$.refreshToken");
        mvc.perform(json(post("/api/admin/auth/logout"), "{\"refreshToken\":\"" + refresh + "\"}").header("Authorization", "Bearer " + access))
                .andExpect(status().isNoContent());
        createAdmin(access, "sau-dang-xuat-" + System.nanoTime() + "@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    void firstAdminSeedIsIdempotent() {
        admins.seedFirstAdmin("seed-" + System.nanoTime() + "@example.com", PASSWORD);
        assertThat(users.existsWithRole(Role.ADMIN)).isTrue();
        assertThat(admins.seedFirstAdmin("seed2-" + System.nanoTime() + "@example.com", PASSWORD)).isFalse();
    }

    private String adminAccess() throws Exception {
        return JsonPath.read(adminLogin(adminEmail, PASSWORD).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions adminLogin(String email, String password) throws Exception {
        return mvc.perform(json(post("/api/admin/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions createAdmin(String access, String email) throws Exception {
        MockHttpServletRequestBuilder req = json(post("/api/admin/manage/admins"), "{\"email\":\"" + email + "\",\"name\":\"Quản trị 2\"}");
        if (access != null) {
            req = req.header("Authorization", "Bearer " + access);
        }
        return mvc.perform(req);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        String ip = "10.40." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr(ip); return r; });
    }

    private String user(String prefix, Role role) {
        User u = new User(prefix + "-" + System.nanoTime() + "@example.com", passwords.encode(PASSWORD), prefix);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getEmail();
    }
}
