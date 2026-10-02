package com.bonbon.backend.authentication;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RegistrationTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    TokenService tokens;

    @MockitoBean
    EmailSender emailSender;

    String customerTermsId;
    String sellerTermsId;
    String privacyId;

    @BeforeEach
    void loadCurrentDocuments() throws Exception {
        customerTermsId = docId("CUSTOMER_TERMS");
        sellerTermsId = docId("SELLER_TERMS");
        privacyId = docId("PRIVACY_POLICY");
        clearInvocations(emailSender);
    }

    @Test
    void newCustomerIsCreatedUnverifiedWithConsentAndCanVerifyByLink() throws Exception {
        String email = unique("an");
        mvc.perform(register(email, "matkhau123", "An", "CUSTOMER", customerTermsId, privacyId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VERIFICATION_SENT"));

        User user = users.findByEmail(email).orElseThrow();
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getRoles()).containsExactly(Role.CUSTOMER);
        Integer consents = jdbc.sql("SELECT count(*) FROM consent_records WHERE principal_id = :id AND purpose = 'TERMS' AND granted")
                .param("id", user.getId()).query(Integer.class).single();
        assertThat(consents).isEqualTo(2);

        String rawToken = tokenFromEmailTo(email);
        mvc.perform(get("/api/auth/verify-email").param("token", rawToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        mvc.perform(get("/api/auth/verify-email").param("token", rawToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_VERIFIED"));
        assertThat(users.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    void registrationWithoutConsentIsRejected() throws Exception {
        mvc.perform(register(unique("noconsent"), "matkhau123", "Binh", "CUSTOMER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void outdatedDocumentVersionIsRejectedWithConflict() throws Exception {
        mvc.perform(register(unique("stale"), "matkhau123", "Chi", "CUSTOMER", UUID.randomUUID().toString(), privacyId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGAL_DOCUMENTS_CHANGED"));
    }

    @Test
    void shortPasswordFailsValidation() throws Exception {
        mvc.perform(register(unique("short"), "1234567", "Dung", "CUSTOMER", customerTermsId, privacyId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void sameRoleTwiceIsRejected() throws Exception {
        String email = unique("dup");
        mvc.perform(register(email, "matkhau123", "Em", "CUSTOMER", customerTermsId, privacyId)).andExpect(status().isCreated());
        mvc.perform(register(email.toUpperCase(), "matkhau123", "Em", "CUSTOMER", customerTermsId, privacyId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void verifiedCustomerAddsSellerRoleWithPassword() throws Exception {
        String email = verifiedUser("giang", Role.CUSTOMER);

        mvc.perform(register(email, "sai-mat-khau", null, "SELLER", sellerTermsId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mvc.perform(register(email, "matkhau123", null, "SELLER", sellerTermsId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLE_ADDED"))
                .andExpect(jsonPath("$.tokens").doesNotExist());
        assertThat(users.findByEmail(email).orElseThrow().getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.SELLER);
        verify(emailSender, never()).send(any());
    }

    @Test
    void unverifiedIdentityCannotGetASecondRole() throws Exception {
        String email = unique("hoa");
        mvc.perform(register(email, "matkhau123", "Hoa", "CUSTOMER", customerTermsId, privacyId)).andExpect(status().isCreated());
        mvc.perform(register(email, "matkhau123", null, "SELLER", sellerTermsId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_NOT_VERIFIED"));
    }

    @Test
    void signedInCustomerAddsSellerRoleAndGetsFreshTokens() throws Exception {
        String email = verifiedUser("khanh", Role.CUSTOMER);
        User user = users.findByEmail(email).orElseThrow();
        String access = tokens.issue(user, Role.CUSTOMER, Instant.now()).accessToken();

        mvc.perform(withIp(post("/api/auth/register")).header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, null, null, "SELLER", sellerTermsId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLE_ADDED"))
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.role").value("CUSTOMER"));
    }

    @Test
    void sixthRegistrationFromOneIpInAnHourIsRefused() throws Exception {
        String ip = "10.9.9." + IP.incrementAndGet();
        for (int i = 0; i < 5; i++) {
            mvc.perform(register(unique("burst" + i), "matkhau123", "Burst", "CUSTOMER", customerTermsId, privacyId)
                    .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isCreated());
        }
        mvc.perform(register(unique("burst6"), "matkhau123", "Burst", "CUSTOMER", customerTermsId, privacyId)
                        .with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void resendAnswersTheSameForUnknownAndSendsForUnverified() throws Exception {
        mvc.perform(withIp(post("/api/auth/resend-verification")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + unique("ghost") + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("IF_NEEDED_SENT"));
        verify(emailSender, never()).send(any());

        String email = unique("lan");
        mvc.perform(register(email, "matkhau123", "Lan", "CUSTOMER", customerTermsId, privacyId)).andExpect(status().isCreated());
        tokenFromEmailTo(email);
        clearInvocations(emailSender);

        mvc.perform(withIp(post("/api/auth/resend-verification")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted());
        assertThat(tokenFromEmailTo(email)).isNotBlank();
    }

    @Test
    void invalidVerificationTokenIsRejected() throws Exception {
        mvc.perform(get("/api/auth/verify-email").param("token", "khong-hop-le"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void adminCannotBeSelfRegistered() throws Exception {
        mvc.perform(register(unique("admin"), "matkhau123", "Admin", "ADMIN", customerTermsId, privacyId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_SELF_REGISTERABLE"));
    }

    // --- helpers

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

    private String tokenFromEmailTo(String email) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, timeout(5000).atLeastOnce()).send(captor.capture());
        EmailMessage message = captor.getAllValues().stream().filter(m -> m.to().equals(email)).reduce((a, b) -> b).orElseThrow();
        Matcher m = TOKEN.matcher(message.textBody());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MockHttpServletRequestBuilder register(String email, String password, String name, String role, String... docIds) {
        return withIp(post("/api/auth/register")).contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password, name, role, docIds));
    }

    private static String json(String email, String password, String name, String role, String... docIds) {
        StringBuilder sb = new StringBuilder("{\"email\":\"").append(email).append("\",\"role\":\"").append(role).append('"');
        if (password != null) sb.append(",\"password\":\"").append(password).append('"');
        if (name != null) sb.append(",\"name\":\"").append(name).append('"');
        if (docIds.length > 0) {
            sb.append(",\"acceptedDocumentIds\":[");
            for (int i = 0; i < docIds.length; i++) sb.append(i > 0 ? "," : "").append('"').append(docIds[i]).append('"');
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder) {
        String ip = "10.0." + (IP.incrementAndGet() / 250) + "." + (IP.get() % 250 + 1);
        return builder.with(r -> { r.setRemoteAddr(ip); return r; });
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime() + "@example.com";
    }
}
