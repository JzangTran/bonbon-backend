package com.bonbon.backend.authentication;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.AuthProvider;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.gateway.GoogleIdTokenVerifier;
import com.bonbon.backend.authentication.gateway.GoogleIdentity;
import com.bonbon.backend.authentication.repository.UserAuthProviderRepository;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OAuthLoginTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    UserAuthProviderRepository providers;

    @Autowired
    PasswordEncoder passwords;

    /** Each test maps its own opaque token strings to Google identities. */
    @MockitoBean
    GoogleIdTokenVerifier google;

    String customerTermsId;
    String sellerTermsId;
    String privacyId;

    @BeforeEach
    void setUp() throws Exception {
        when(google.isEnabled()).thenReturn(true);
        when(google.verify(any())).thenReturn(Optional.empty());
        customerTermsId = docId("CUSTOMER_TERMS");
        sellerTermsId = docId("SELLER_TERMS");
        privacyId = docId("PRIVACY_POLICY");
    }

    @Test
    void newGoogleIdentityIsAskedForRoleAndConsentThenCreatedVerified() throws Exception {
        String email = unique("moi");
        String token = googleToken(email, true);
        oauth(Map.of("provider", "GOOGLE", "token", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OAUTH_SIGNUP_REQUIRED"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Người Dùng Google"));
        assertThat(users.findByEmail(email)).isEmpty();

        oauth(Map.of("provider", "GOOGLE", "token", token, "role", "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));

        oauth(Map.of("provider", "GOOGLE", "token", token, "role", "CUSTOMER",
                "acceptedDocumentIds", new String[] {customerTermsId, privacyId}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.activeRole").value("CUSTOMER"));
        User created = users.findByEmail(email).orElseThrow();
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.hasPassword()).isFalse();
        assertThat(created.getAvatarUrl()).isEqualTo("https://example.com/a.png");
        assertThat(providers.isLinked(created.getId(), AuthProvider.GOOGLE)).isTrue();

        // Next time the provider identity alone signs in.
        oauth(Map.of("provider", "GOOGLE", "token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(created.getId().toString()));
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        oauth(Map.of("provider", "GOOGLE", "token", "forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_OAUTH_TOKEN"));
    }

    @Test
    void unverifiedGoogleEmailNeverCreatesOrLinks() throws Exception {
        String email = verifiedUser("chuaxacminh", Role.CUSTOMER);
        oauth(Map.of("provider", "GOOGLE", "token", googleToken(email, false), "password", "matkhau123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_EMAIL_UNVERIFIED"));
        assertThat(providers.isLinked(users.findByEmail(email).orElseThrow().getId(), AuthProvider.GOOGLE)).isFalse();
    }

    @Test
    void existingPasswordAccountLinksOnlyWithItsPassword() throws Exception {
        String email = verifiedUser("lienket", Role.CUSTOMER);
        UUID id = users.findByEmail(email).orElseThrow().getId();
        String token = googleToken(email, true);

        oauth(Map.of("provider", "GOOGLE", "token", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_EXISTS_LINK_REQUIRED"))
                .andExpect(jsonPath("$.linkMethod").value("PASSWORD"));
        oauth(Map.of("provider", "GOOGLE", "token", token, "password", "sai-mat-khau"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(providers.isLinked(id, AuthProvider.GOOGLE)).isFalse();

        oauth(Map.of("provider", "GOOGLE", "token", token, "password", "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id.toString()));
        assertThat(providers.isLinked(id, AuthProvider.GOOGLE)).isTrue();

        oauth(Map.of("provider", "GOOGLE", "token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(id.toString()));
    }

    @Test
    void unverifiedLocalAccountIsNeverLinked() throws Exception {
        User user = new User(unique("chuaverify"), passwords.encode("matkhau123"), "Chưa xác thực");
        user.addRole(Role.CUSTOMER);
        String email = users.saveAndFlush(user).getEmail();
        oauth(Map.of("provider", "GOOGLE", "token", googleToken(email, true), "password", "matkhau123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.linkMethod").value("VERIFY_EMAIL_FIRST"));
    }

    @Test
    void adminEmailCannotBeTakenOverThroughGoogle() throws Exception {
        String email = verifiedUser("quantri", Role.ADMIN);
        oauth(Map.of("provider", "GOOGLE", "token", googleToken(email, true), "password", "matkhau123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void secondRoleIsAddedWithItsTermsAndThenLoginAsksForTheRole() throws Exception {
        String email = unique("haivaitro");
        String token = googleToken(email, true);
        oauth(Map.of("provider", "GOOGLE", "token", token, "role", "CUSTOMER",
                "acceptedDocumentIds", new String[] {customerTermsId, privacyId})).andExpect(status().isOk());

        oauth(Map.of("provider", "GOOGLE", "token", token, "role", "SELLER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        oauth(Map.of("provider", "GOOGLE", "token", token, "role", "SELLER",
                "acceptedDocumentIds", new String[] {sellerTermsId}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"));

        oauth(Map.of("provider", "GOOGLE", "token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsRoleSelection").value(true))
                .andExpect(jsonPath("$.roleToken").isNotEmpty());
    }

    @Test
    void googleEmailChangeIsSyncedButNameIsKept() throws Exception {
        String email = unique("doiemail");
        String subject = "sub-" + UUID.randomUUID();
        String first = googleToken(subject, email, true);
        oauth(Map.of("provider", "GOOGLE", "token", first, "role", "CUSTOMER",
                "acceptedDocumentIds", new String[] {customerTermsId, privacyId})).andExpect(status().isOk());
        User user = users.findByEmail(email).orElseThrow();
        user.setName("Tên tự đặt");
        users.saveAndFlush(user);

        String newEmail = unique("emailmoi");
        oauth(Map.of("provider", "GOOGLE", "token", googleToken(subject, newEmail, true))).andExpect(status().isOk());
        User synced = users.findById(user.getId()).orElseThrow();
        assertThat(synced.getEmail()).isEqualTo(newEmail);
        assertThat(synced.getName()).isEqualTo("Tên tự đặt");
    }

    @Test
    void facebookIsNotSupportedYet() throws Exception {
        oauth(Map.of("provider", "FACEBOOK", "token", "anything"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_SUPPORTED"));
    }

    // --- helpers

    private String googleToken(String email, boolean emailVerified) {
        return googleToken("sub-" + UUID.randomUUID(), email, emailVerified);
    }

    private String googleToken(String subject, String email, boolean emailVerified) {
        String token = "google-" + UUID.randomUUID();
        when(google.verify(token)).thenReturn(Optional.of(
                new GoogleIdentity(subject, email, emailVerified, "Người Dùng Google", "https://example.com/a.png")));
        return token;
    }

    private ResultActions oauth(Map<String, Object> body) throws Exception {
        String ip = "10.30." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
        return mvc.perform(post("/api/auth/login/oauth").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)).with(r -> { r.setRemoteAddr(ip); return r; }));
    }

    private String docId(String type) throws Exception {
        String body = mvc.perform(get("/api/legal/documents/" + type)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String verifiedUser(String prefix, Role role) {
        User user = new User(unique(prefix), passwords.encode("matkhau123"), prefix);
        user.addRole(role);
        user.markEmailVerified();
        return users.saveAndFlush(user).getEmail();
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime() + "@example.com";
    }
}
