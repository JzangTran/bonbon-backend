package com.bonbon.backend.authentication;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.authentication.service.TokenService.TokenPair;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.common.mail.EmailSender.EmailMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
@Import({TestcontainersConfiguration.class, SessionAndPasswordTests.ProbeController.class})
class SessionAndPasswordTests {

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

    @MockitoBean
    EmailSender emailSender;

    User user;

    @BeforeEach
    void setUp() {
        clearInvocations(emailSender);
        User u = new User("session-" + System.nanoTime() + "@example.com", passwords.encode(PASSWORD), "Session");
        u.addRole(Role.CUSTOMER);
        u.markEmailVerified();
        user = users.saveAndFlush(u);
    }

    @Test
    void logoutKillsThisDeviceOnly() throws Exception {
        TokenPair phone = tokens.issue(user, Role.CUSTOMER, Instant.now());
        TokenPair laptop = tokens.issue(user, Role.CUSTOMER, Instant.now());

        mvc.perform(json(post("/api/auth/logout"), "{\"refreshToken\":\"" + phone.refreshToken() + "\"}")
                        .header("Authorization", "Bearer " + phone.accessToken()))
                .andExpect(status().isNoContent());

        probe(phone.accessToken()).andExpect(status().isUnauthorized());
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        probe(laptop.accessToken()).andExpect(status().isOk());
    }

    @Test
    void logoutAllNeedsThePasswordAndThenEndsEverySession() throws Exception {
        TokenPair a = tokens.issue(user, Role.CUSTOMER, Instant.now());
        TokenPair b = tokens.issue(user, Role.CUSTOMER, Instant.now());

        mvc.perform(json(post("/api/auth/logout-all"), "{\"password\":\"sai\"}").header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isUnauthorized());
        probe(b.accessToken()).andExpect(status().isOk());

        Thread.sleep(1100); // iat has second precision
        mvc.perform(json(post("/api/auth/logout-all"), "{\"password\":\"" + PASSWORD + "\"}").header("Authorization", "Bearer " + a.accessToken()))
                .andExpect(status().isNoContent());
        probe(a.accessToken()).andExpect(status().isUnauthorized());
        probe(b.accessToken()).andExpect(status().isUnauthorized());
        refresh(b.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordAnswersTheSameAndSendsOnlyForRealAccounts() throws Exception {
        mvc.perform(json(post("/api/auth/forgot-password"), "{\"email\":\"khong-co-" + System.nanoTime() + "@example.com\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("IF_EXISTS_SENT"));
        verify(emailSender, never()).send(any());

        mvc.perform(json(post("/api/auth/forgot-password"), "{\"email\":\"" + user.getEmail() + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("IF_EXISTS_SENT"));
        assertThat(resetTokenFromEmail()).isNotBlank();
    }

    @Test
    void resettingThePasswordSignsOutEverywhereAndTheLinkWorksOnce() throws Exception {
        TokenPair before = tokens.issue(user, Role.CUSTOMER, Instant.now());
        mvc.perform(json(post("/api/auth/forgot-password"), "{\"email\":\"" + user.getEmail() + "\"}")).andExpect(status().isAccepted());
        String rawToken = resetTokenFromEmail();

        Thread.sleep(1100);
        mvc.perform(json(post("/api/auth/reset-password"), "{\"token\":\"" + rawToken + "\",\"newPassword\":\"matkhaumoi456\"}"))
                .andExpect(status().isNoContent());

        probe(before.accessToken()).andExpect(status().isUnauthorized());
        refresh(before.refreshToken()).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"" + user.getEmail() + "\",\"password\":\"matkhaumoi456\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/reset-password"), "{\"token\":\"" + rawToken + "\",\"newPassword\":\"matkhaukhac789\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_TOKEN"));
    }

    @Test
    void logoutWithoutASessionIsUnauthorized() throws Exception {
        mvc.perform(json(post("/api/auth/logout"), "{}")).andExpect(status().isUnauthorized());
    }

    private String resetTokenFromEmail() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, timeout(5000).atLeastOnce()).send(captor.capture());
        EmailMessage message = captor.getValue();
        assertThat(message.to()).isEqualTo(user.getEmail());
        assertThat(message.textBody()).contains("/reset-password?token=");
        Matcher m = TOKEN.matcher(message.textBody());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions probe(String accessToken) throws Exception {
        return mvc.perform(get("/api/probe/session").header("Authorization", "Bearer " + accessToken));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(json(post("/api/auth/refresh"), "{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        String ip = "10.30." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr(ip); return r; });
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe/session")
        String session() {
            return "ok";
        }
    }
}
