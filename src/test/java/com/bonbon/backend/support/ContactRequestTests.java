package com.bonbon.backend.support;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.mail.EmailSender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
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

/** The public contact form: anyone writes, administrators answer by email, closing, validation and the limits. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ContactRequestTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @MockitoBean
    EmailSender emailSender;

    String admin;
    String customer;

    @BeforeEach
    void setUp() {
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
    }

    @Test
    void aGuestWritesAndTheAnswerGoesToTheirEmail() throws Exception {
        String email = "guest-" + System.nanoTime() + "@example.com";
        String id = submit(email, "10.0.0." + (System.nanoTime() % 200)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        id = JsonPath.read(id, "$.id");

        call(admin, get("/api/admin/support/contact-requests"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id=='" + id + "')].email").value(email));
        call(admin, get("/api/admin/support/contact-requests/" + id), null).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.fullName").value("Lan"))
                .andExpect(jsonPath("$.answers.length()").value(0));

        call(admin, post("/api/admin/support/contact-requests/" + id + "/answer"), Map.of("body", "Bạn vào Tài khoản rồi chọn Đổi mật khẩu nhé"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED")).andExpect(jsonPath("$.answers[0].adminName").value("admin"));
        ArgumentCaptor<EmailSender.EmailMessage> sent = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        Mockito.verify(emailSender, Mockito.timeout(10_000)).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo(email);
        assertThat(sent.getValue().textBody()).contains("Đổi mật khẩu").contains("Mình không đổi được mật khẩu");

        call(admin, post("/api/admin/support/contact-requests/" + id + "/close"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        call(admin, post("/api/admin/support/contact-requests/" + id + "/answer"), Map.of("body", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUEST_CLOSED"));
    }

    @Test
    void onlyAdministratorsSeeTheInboxAndTheFormIsChecked() throws Exception {
        call(customer, get("/api/admin/support/contact-requests"), null).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/support/contact-requests")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("fullName", "", "email", "not-an-email", "role", "ADMIN", "topic", "ORDER", "message", "x"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oneEmailSendsAtMostThreeADay() throws Exception {
        String email = "busy-" + System.nanoTime() + "@example.com";
        for (int i = 0; i < 3; i++) {
            submit(email, "10.1." + i + "." + (System.nanoTime() % 200)).andExpect(status().isCreated());
        }
        submit(email, "10.2.0." + (System.nanoTime() % 200)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_CONTACT_REQUESTS"));
    }

    private ResultActions submit(String email, String ip) throws Exception {
        return mvc.perform(post("/api/public/contact-requests").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("fullName", "Lan", "email", email, "role", "CUSTOMER", "topic", "ACCOUNT",
                "message", "Mình không đổi được mật khẩu", "captchaToken", "dev-captcha"))));
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
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
