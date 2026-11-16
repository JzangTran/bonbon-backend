package com.bonbon.backend.identity.authentication;

import java.util.concurrent.atomic.AtomicInteger;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** UC-002b Log in to the seller web app with a QR code; the cases follow the test table in docs/report (QR-01 … QR-15). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class QrLoginTests {

    private static final AtomicInteger IP = new AtomicInteger(1);
    private static final String PASSWORD = "Bep-Sen-2027";
    private static final String CHROME_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwords;

    String ip;

    @BeforeEach
    void setUp() {
        ip = "10.77." + (IP.get() / 250) + "." + (IP.incrementAndGet() % 250 + 1);
    }

    record Session(String sessionId, String pollToken) {
        String poll() {
            return "{\"sessionId\":\"" + sessionId + "\",\"pollToken\":\"" + pollToken + "\"}";
        }

        String scan() {
            return "{\"sessionId\":\"" + sessionId + "\"}";
        }
    }

    @Test
    void startGivesACodeThatIsPending() throws Exception {
        String body = mvc.perform(web(post("/api/auth/qr-login/start"), ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(180))
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(body, "$.sessionId");
        String pollToken = JsonPath.read(body, "$.pollToken");
        assertThat(sessionId).hasSize(43).isNotEqualTo(pollToken);
        assertThat((String) JsonPath.read(body, "$.qrContent")).isEqualTo("bonbon://qr-login?s=" + sessionId);
        assertThat((String) JsonPath.read(body, "$.qrContent")).doesNotContain(pollToken);
        state(new Session(sessionId, pollToken)).andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void aWrongPollTokenLooksExpired() throws Exception {
        Session s = start();
        Session forged = new Session(s.sessionId(), s.sessionId());
        state(forged).andExpect(jsonPath("$.state").value("EXPIRED"));
        mvc.perform(web(post("/api/auth/qr-login/exchange"), forged.poll())).andExpect(status().isGone());
    }

    @Test
    void scanningNeedsASignedInPhone() throws Exception {
        Session s = start();
        mvc.perform(web(post("/api/auth/qr-login/scan"), s.scan()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aCustomerOnlyIdentityCannotConfirm() throws Exception {
        Session s = start();
        String customer = token(user("kh", Role.CUSTOMER));
        phone("scan", s, customer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_SELLER_ACCESS"));
        state(s).andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void scanShowsTheWaitingBrowserAndDoesNotSignIn() throws Exception {
        Session s = start();
        phone("scan", s, token(user("nb", Role.SELLER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device").value("Chrome trên Windows"))
                .andExpect(jsonPath("$.ip").value(ip));
        state(s).andExpect(jsonPath("$.state").value("SCANNED"));
        mvc.perform(web(post("/api/auth/qr-login/exchange"), s.poll()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QR_NOT_CONFIRMED"));
    }

    @Test
    void aDualRoleIdentitySignedInAsCustomerCanConfirm() throws Exception {
        Session s = start();
        String both = user("hai", Role.CUSTOMER, Role.SELLER);
        String roleToken = JsonPath.read(login(both).andReturn().getResponse().getContentAsString(), "$.roleToken");
        String body = mvc.perform(web(post("/api/auth/select-role"), "{\"roleToken\":\"" + roleToken + "\",\"role\":\"CUSTOMER\"}"))
                .andReturn().getResponse().getContentAsString();
        String customerSession = JsonPath.read(body, "$.tokens.accessToken");
        phone("scan", s, customerSession).andExpect(status().isOk());
        phone("confirm", s, customerSession).andExpect(status().isNoContent());
        exchange(s).andExpect(status().isOk()).andExpect(jsonPath("$.user.activeRole").value("SELLER"));
    }

    @Test
    void anotherAccountCannotTakeOverAScannedCode() throws Exception {
        Session s = start();
        phone("scan", s, token(user("nb1", Role.SELLER))).andExpect(status().isOk());
        String other = token(user("nb2", Role.SELLER));
        phone("scan", s, other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_ALREADY_SCANNED"));
        phone("confirm", s, other).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QR_ALREADY_SCANNED"));
        state(s).andExpect(jsonPath("$.state").value("SCANNED"));
    }

    @Test
    void confirmThenExchangeSignsTheWebInAsSellerOnce() throws Exception {
        Session s = start();
        String seller = token(user("nb", Role.SELLER));
        phone("scan", s, seller).andExpect(status().isOk());
        phone("confirm", s, seller).andExpect(status().isNoContent());
        state(s).andExpect(jsonPath("$.state").value("CONFIRMED"));
        exchange(s)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.activeRole").value("SELLER"));
        exchange(s).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("QR_EXPIRED"));
        state(s).andExpect(jsonPath("$.state").value("EXPIRED"));
    }

    @Test
    void rejectingStopsTheSignIn() throws Exception {
        Session s = start();
        String seller = token(user("nb", Role.SELLER));
        phone("scan", s, seller).andExpect(status().isOk());
        phone("reject", s, seller).andExpect(status().isNoContent());
        state(s).andExpect(jsonPath("$.state").value("REJECTED"));
        exchange(s).andExpect(status().isConflict());
        phone("confirm", s, seller).andExpect(status().isGone());
    }

    @Test
    void confirmingWithoutScanningIsRefused() throws Exception {
        Session s = start();
        phone("confirm", s, token(user("nb", Role.SELLER))).andExpect(status().isGone());
    }

    @Test
    void anUnknownCodeIsExpired() throws Exception {
        Session unknown = new Session("A".repeat(43), "B".repeat(43));
        phone("scan", unknown, token(user("nb", Role.SELLER)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("QR_EXPIRED"));
    }

    @Test
    void aMalformedCodeIsRejectedBeforeRedis() throws Exception {
        String seller = token(user("nb", Role.SELLER));
        for (String bad : new String[] {"", "short", "x".repeat(44), "has space in it 0123456789012345678901234567"}) {
            mvc.perform(web(post("/api/auth/qr-login/scan"), "{\"sessionId\":\"" + bad + "\"}")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + seller))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(web(post("/api/auth/qr-login/status"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void startIsLimitedPerAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(web(post("/api/auth/qr-login/start"), "")).andExpect(status().isOk());
        }
        mvc.perform(web(post("/api/auth/qr-login/start"), ""))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void theDeviceDescriptionIsReadable() {
        assertThat(com.bonbon.backend.identity.authentication.service.QrLoginService.describe(CHROME_WINDOWS)).isEqualTo("Chrome trên Windows");
        assertThat(com.bonbon.backend.identity.authentication.service.QrLoginService.describe(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"))
                .isEqualTo("Safari trên macOS");
        assertThat(com.bonbon.backend.identity.authentication.service.QrLoginService.describe(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/131.0 Safari/537.36 Edg/131.0")).isEqualTo("Edge trên Windows");
        assertThat(com.bonbon.backend.identity.authentication.service.QrLoginService.describe(null)).isEqualTo("Trình duyệt không rõ");
    }

    private Session start() throws Exception {
        String body = mvc.perform(web(post("/api/auth/qr-login/start"), ""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.sessionId"), JsonPath.read(body, "$.pollToken"));
    }

    private ResultActions state(Session s) throws Exception {
        return mvc.perform(web(post("/api/auth/qr-login/status"), s.poll())).andExpect(status().isOk());
    }

    private ResultActions exchange(Session s) throws Exception {
        return mvc.perform(web(post("/api/auth/qr-login/exchange"), s.poll()));
    }

    private ResultActions phone(String action, Session s, String accessToken) throws Exception {
        return mvc.perform(web(post("/api/auth/qr-login/" + action), s.scan())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("X-Client-Channel", "MOBILE"));
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(web(post("/api/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .header("X-Client-Channel", "MOBILE"));
    }

    private String token(String email) throws Exception {
        return JsonPath.read(login(email).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.tokens.accessToken");
    }

    private MockHttpServletRequestBuilder web(MockHttpServletRequestBuilder builder, String body) {
        String clientIp = ip;
        return builder.contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HttpHeaders.USER_AGENT, CHROME_WINDOWS)
                .with(r -> { r.setRemoteAddr(clientIp); return r; });
    }

    private String user(String prefix, Role... roles) {
        User user = new User(prefix + "-" + System.nanoTime() + "@example.com", passwords.encode(PASSWORD), prefix);
        for (Role role : roles) {
            user.addRole(role);
        }
        user.markEmailVerified();
        return users.saveAndFlush(user).getEmail();
    }
}
