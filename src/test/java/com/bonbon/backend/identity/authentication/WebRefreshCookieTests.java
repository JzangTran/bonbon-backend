package com.bonbon.backend.identity.authentication;

import java.util.concurrent.atomic.AtomicInteger;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web app's refresh token travels only in an HttpOnly cookie; the phone app's stays in the body. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WebRefreshCookieTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "matkhau123";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwords;

    String email;
    String ip;

    @BeforeEach
    void setUp() {
        email = "web-cookie-" + System.nanoTime() + "@example.com";
        ip = "10.77.0." + IP.getAndIncrement();
        User u = new User(email, passwords.encode(PASSWORD), "Web");
        u.addRole(Role.SELLER);
        u.markEmailVerified();
        users.saveAndFlush(u);
    }

    @Test
    void webSignInPutsTheRefreshTokenInAnHttpOnlyCookieOnly() throws Exception {
        MvcResult login = login("WEB");
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("bonbon_rt=").contains("HttpOnly").contains("Path=/api").contains("SameSite=Lax");
        assertThat(login.getResponse().getContentAsString()).doesNotContain("refreshToken");
    }

    @Test
    void webRefreshUsesTheCookieRotatesItAndSaysWhoIsSignedIn() throws Exception {
        Cookie first = cookieOf(login("WEB"));

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").header("X-Client-Channel", "WEB").cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"))
                .andReturn();
        Cookie second = cookieOf(refreshed);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // the old cookie was rotated away
        mvc.perform(post("/api/auth/refresh").header("X-Client-Channel", "WEB").cookie(first)).andExpect(status().isUnauthorized());
    }

    @Test
    void webSignOutWithOnlyTheCookieRevokesItAndClearsIt() throws Exception {
        Cookie cookie = cookieOf(login("WEB"));

        MvcResult out = mvc.perform(post("/api/auth/logout").header("X-Client-Channel", "WEB").cookie(cookie))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        mvc.perform(post("/api/auth/refresh").header("X-Client-Channel", "WEB").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void thePhoneAppStillGetsItsRefreshTokenInTheBody() throws Exception {
        MvcResult login = login("MOBILE");
        assertThat(login.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(login.getResponse().getContentAsString()).contains("refreshToken");
    }

    @Test
    void refreshWithNothingIsUnauthorized() throws Exception {
        mvc.perform(post("/api/auth/refresh").header("X-Client-Channel", "WEB"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    private MvcResult login(String channel) throws Exception {
        String clientIp = ip;
        return mvc.perform(post("/api/auth/login")
                        .header("X-Client-Channel", channel)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(r -> { r.setRemoteAddr(clientIp); return r; }))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static Cookie cookieOf(MvcResult result) {
        Cookie c = result.getResponse().getCookie("bonbon_rt");
        assertThat(c).isNotNull();
        return c;
    }
}
