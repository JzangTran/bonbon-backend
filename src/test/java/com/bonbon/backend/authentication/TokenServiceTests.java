package com.bonbon.backend.authentication;

import java.time.Instant;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.authentication.service.TokenService.TokenPair;
import com.bonbon.backend.common.security.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TokenServiceTests.ProbeController.class})
class TokenServiceTests {

    @Autowired
    TokenService tokens;

    @Autowired
    UserRepository users;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    MockMvc mvc;

    User customer;

    @BeforeEach
    void createUser() {
        User u = new User("token-" + System.nanoTime() + "@example.com", "hash", "Token Tester");
        u.addRole(Role.CUSTOMER);
        u.markEmailVerified();
        customer = users.saveAndFlush(u);
    }

    @Test
    void accessTokenCarriesRolePermissionsAndThirtyMinuteLifetime() {
        TokenPair pair = tokens.issue(customer, Role.CUSTOMER, Instant.now());
        Jwt jwt = decoder.decode(pair.accessToken());

        assertThat(jwt.getSubject()).isEqualTo(customer.getId().toString());
        assertThat(jwt.getClaimAsString(CurrentPrincipal.CLAIM_ROLE)).isEqualTo("CUSTOMER");
        assertThat(jwt.getClaimAsString(CurrentPrincipal.CLAIM_ACTOR)).isEqualTo("CUSTOMER");
        assertThat(jwt.getClaimAsStringList(CurrentPrincipal.CLAIM_PERMISSIONS)).contains("order:create", "order:cancel");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(1800);
        assertThat(pair.expiresInSeconds()).isEqualTo(1800);
    }

    @Test
    void realTokenAuthenticatesAProtectedEndpoint() throws Exception {
        TokenPair pair = tokens.issue(customer, Role.CUSTOMER, Instant.now());
        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customer.getId().toString()))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheFamily() throws Exception {
        TokenPair first = tokens.issue(customer, Role.CUSTOMER, Instant.now());

        String body = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.refreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String second = com.jayway.jsonpath.JsonPath.read(body, "$.refreshToken");
        assertThat(second).isNotEqualTo(first.refreshToken());

        // the rotated-out token is presented again: theft signal
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // ... so the legitimate newer token of the same family is dead too
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + second + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-real-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void loggedOutAccessTokenIsRefusedImmediately() throws Exception {
        TokenPair pair = tokens.issue(customer, Role.CUSTOMER, Instant.now());
        tokens.revokeSession(decoder.decode(pair.accessToken()), pair.refreshToken());

        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + pair.accessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + pair.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidatingAllSessionsKillsOlderTokensButNotNewOnes() throws Exception {
        TokenPair old = tokens.issue(customer, Role.CUSTOMER, Instant.now());
        Thread.sleep(1100); // iat has second precision
        tokens.invalidateAllSessions(users.findById(customer.getId()).orElseThrow());
        TokenPair fresh = tokens.issue(customer, Role.CUSTOMER, Instant.now());

        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + old.accessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/probe/me").header("Authorization", "Bearer " + fresh.accessToken()))
                .andExpect(status().isOk());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe/me")
        CurrentPrincipal me(CurrentPrincipal principal) {
            return principal;
        }
    }
}
