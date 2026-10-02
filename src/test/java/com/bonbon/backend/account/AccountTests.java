package com.bonbon.backend.account;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "matkhau123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    TokenService tokens;

    User user;

    @BeforeEach
    void setUp() {
        User u = new User("account-" + System.nanoTime() + "@example.com", passwords.encode(PASSWORD), "Lan Anh");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        user = users.saveAndFlush(u);
    }

    @Test
    void readsOwnProfile() throws Exception {
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.name").value("Lan Anh"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.roles[0]").value("SELLER"));
        mvc.perform(get("/api/account/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void updatesNameAndVietnamesePhone() throws Exception {
        mvc.perform(json(patch("/api/account/me"), "{\"name\":\"Lan Anh Trần\",\"phone\":\"0912345678\"}")
                        .header("Authorization", "Bearer " + access()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lan Anh Trần"))
                .andExpect(jsonPath("$.phone").value("0912345678"));
        mvc.perform(json(patch("/api/account/me"), "{\"phone\":\"12345\"}").header("Authorization", "Bearer " + access()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
    }

    @Test
    void changingThePasswordKeepsThisSessionAndEndsTheOthers() throws Exception {
        String otherDevice = access();
        String thisDevice = access();
        Thread.sleep(1100); // iat has second precision

        mvc.perform(json(post("/api/account/change-password"), "{\"currentPassword\":\"sai\",\"newPassword\":\"matkhaumoi456\"}")
                        .header("Authorization", "Bearer " + thisDevice))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        String body = mvc.perform(json(post("/api/account/change-password"),
                        "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"matkhaumoi456\"}")
                        .header("Authorization", "Bearer " + thisDevice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andReturn().getResponse().getContentAsString();
        String fresh = JsonPath.read(body, "$.accessToken");

        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + otherDevice)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
        mvc.perform(json(post("/api/auth/login"), "{\"email\":\"" + user.getEmail() + "\",\"password\":\"matkhaumoi456\"}"))
                .andExpect(status().isOk());
    }

    private String access() {
        return tokens.issue(users.findById(user.getId()).orElseThrow(), Role.SELLER, Instant.now()).accessToken();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        String ip = "10.50." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
        return builder.contentType(MediaType.APPLICATION_JSON).content(body).with(r -> { r.setRemoteAddr(ip); return r; });
    }
}
