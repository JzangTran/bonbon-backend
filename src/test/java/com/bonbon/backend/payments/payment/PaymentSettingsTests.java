package com.bonbon.backend.payments.payment;

import java.time.Instant;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cài đặt thanh toán: linking MoMo, the main method, unlinking, and creating then changing the payment PIN. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentSettingsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    String customer;
    String other;

    @BeforeEach
    void setUp() {
        customer = token();
        other = token();
    }

    @Test
    void aLinkedWalletKeepsOnlyItsLastFourDigitsAndTheFirstIsTheMainOne() throws Exception {
        as(customer, get("/api/account/payment-methods")).andExpect(status().isOk())
                .andExpect(jsonPath("$.methods", empty())).andExpect(jsonPath("$.linkable[0]").value("MOMO"));

        String body = as(customer, post("/api/account/payment-methods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"MOMO\",\"account\":\"0912345678\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedAccount").value("******5678"))
                .andExpect(jsonPath("$.name").value("MoMo"))
                .andExpect(jsonPath("$.primary").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        as(customer, get("/api/account/payment-methods")).andExpect(jsonPath("$.methods", hasSize(1))).andExpect(jsonPath("$.linkable", empty()));
        as(customer, post("/api/account/payment-methods").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"MOMO\",\"account\":\"0987654321\"}")).andExpect(status().isConflict());

        as(customer, patch("/api/account/payment-methods/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"primary\":false}"))
                .andExpect(status().isNoContent());
        as(customer, get("/api/account/payment-methods")).andExpect(jsonPath("$.methods[0].primary").value(false));

        // someone else's method is not there for them
        as(other, delete("/api/account/payment-methods/" + id)).andExpect(status().isNotFound());
        as(customer, delete("/api/account/payment-methods/" + id)).andExpect(status().isNoContent());
        as(customer, get("/api/account/payment-methods")).andExpect(jsonPath("$.methods", empty()));
    }

    @Test
    void aPinIsCreatedThenChangedOnlyWithTheCurrentOneAndLocksAfterFiveMistakes() throws Exception {
        as(customer, get("/api/account/payment-pin")).andExpect(jsonPath("$.set").value(false));
        as(customer, pin("{\"newPin\":\"123456\"}")).andExpect(status().isNoContent());
        as(customer, get("/api/account/payment-pin")).andExpect(jsonPath("$.set").value(true));

        as(customer, pin("{\"newPin\":\"654321\"}")).andExpect(status().isUnprocessableContent());
        as(customer, pin("{\"currentPin\":\"123456\",\"newPin\":\"654321\"}")).andExpect(status().isNoContent());
        as(customer, pin("{\"newPin\":\"12345\"}")).andExpect(status().isBadRequest());

        for (int i = 0; i < 5; i++) {
            as(customer, pin("{\"currentPin\":\"000000\",\"newPin\":\"111111\"}")).andExpect(status().isUnprocessableContent());
        }
        as(customer, pin("{\"currentPin\":\"654321\",\"newPin\":\"111111\"}")).andExpect(status().isTooManyRequests());
    }

    private MockHttpServletRequestBuilder pin(String body) {
        return put("/api/account/payment-pin").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private String token() {
        User user = new User("pay-" + System.nanoTime() + "@example.com", null, "Khách");
        user.addRole(Role.CUSTOMER);
        user.markEmailVerified();
        return tokens.issue(users.saveAndFlush(user), Role.CUSTOMER, Instant.now()).accessToken();
    }
}
