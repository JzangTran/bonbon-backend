package com.bonbon.backend.common;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityAndErrorHandlingTests.ProbeController.class)
class SecurityAndErrorHandlingTests {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenIsProblem401() throws Exception {
        mvc.perform(get("/api/probe/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void currentPrincipalComesFromToken() throws Exception {
        mvc.perform(get("/api/probe/me").with(customerToken(List.of("order:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.actorType").value("CUSTOMER"))
                .andExpect(jsonPath("$.activeRole").value("CUSTOMER"));
    }

    @Test
    void missingPermissionIsProblem403() throws Exception {
        mvc.perform(get("/api/probe/admin-only").with(customerToken(List.of("order:read"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void grantedPermissionPasses() throws Exception {
        mvc.perform(get("/api/probe/admin-only").with(customerToken(List.of("refund:process"))))
                .andExpect(status().isOk());
    }

    @Test
    void businessExceptionKeepsItsCode() throws Exception {
        mvc.perform(get("/api/probe/conflict").with(customerToken(List.of())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("PROBE_CONFLICT"));
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        mvc.perform(post("/api/probe/validate").with(customerToken(List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customerToken(List<String> perms) {
        return jwt()
                .jwt(j -> j.subject(USER_ID.toString())
                        .claim(CurrentPrincipal.CLAIM_ACTOR, "CUSTOMER")
                        .claim(CurrentPrincipal.CLAIM_ROLE, "CUSTOMER")
                        .claim(CurrentPrincipal.CLAIM_PERMISSIONS, perms))
                .authorities(perms.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }

    record NameBody(@NotBlank String name) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe/me")
        CurrentPrincipal me(CurrentPrincipal principal) {
            return principal;
        }

        @GetMapping("/api/probe/admin-only")
        @PreAuthorize("hasAuthority('refund:process')")
        String adminOnly() {
            return "ok";
        }

        @GetMapping("/api/probe/conflict")
        String conflict() {
            throw BusinessException.conflict("PROBE_CONFLICT", "Already done.");
        }

        @PostMapping("/api/probe/validate")
        String validate(@Valid @RequestBody NameBody body) {
            return body.name();
        }
    }
}
