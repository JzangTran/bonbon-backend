package com.bonbon.backend.operations.ops;

import java.time.Instant;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The infrastructure status the ops site shows: who may read it and what it reports. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OpsStatusTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Test
    void administratorsReadTheStatusOfTheDatabaseAndRedis() throws Exception {
        mvc.perform(get("/api/admin/ops/status").header("Authorization", "Bearer " + token(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checks.length()").value(2))
                .andExpect(jsonPath("$.checks[0].ok").value(true))
                .andExpect(jsonPath("$.checks[1].name").value("Redis"))
                .andExpect(jsonPath("$.heapMaxMb").isNumber())
                .andExpect(jsonPath("$.uptimeSeconds").isNumber());
    }

    @Test
    void sellersCustomersAndStrangersAreRefused() throws Exception {
        mvc.perform(get("/api/admin/ops/status").header("Authorization", "Bearer " + token(Role.SELLER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ops/status").header("Authorization", "Bearer " + token(Role.CUSTOMER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/ops/status")).andExpect(status().isUnauthorized());
    }

    private String token(Role role) {
        User u = new User(role.name().toLowerCase() + "-" + System.nanoTime() + "@example.com", null, "Người dùng");
        u.addRole(role);
        u.markEmailVerified();
        return tokens.issue(users.saveAndFlush(u), role, Instant.now()).accessToken();
    }
}
