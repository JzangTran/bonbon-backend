package com.bonbon.backend.account;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with the fake geocoder; the spy counts Place Detail calls. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AddressTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @MockitoSpyBean
    Geocoder geocoder;

    String customer;

    @BeforeEach
    void setUp() {
        customer = token(Role.CUSTOMER);
    }

    @Test
    void firstAddressBecomesDefaultAndNewDefaultReplacesTheOld() throws Exception {
        String home = create("Nhà", "Toà S2", true);
        mvc.perform(auth(get("/api/account/addresses"), customer)).andExpect(jsonPath("$[0].isDefault").value(true));
        String work = create("Công ty", "Cổng 3 KCN", false);
        mvc.perform(auth(get("/api/account/addresses"), customer))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(home))
                .andExpect(jsonPath("$[1].isDefault").value(false));

        mvc.perform(auth(patch("/api/account/addresses/" + work + "/default"), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(auth(get("/api/account/addresses"), customer))
                .andExpect(jsonPath("$[0].id").value(work))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].id").value(home))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    void deletingTheDefaultPromotesAnother() throws Exception {
        String home = create("Nhà", "Toà A", false);
        String work = create("Công ty", "Toà B", false);
        mvc.perform(auth(delete("/api/account/addresses/" + home), customer)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/account/addresses"), customer))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(work))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    void editingOnlyTheDetailMakesNoGeocodingCall() throws Exception {
        String id = create("Nhà", "Toà S1", false);
        clearInvocations(geocoder);
        send(patch("/api/account/addresses/" + id), Map.of("detail", "Tầng 12, căn 05", "recipientName", "Lan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("Tầng 12, căn 05"))
                .andExpect(jsonPath("$.recipientName").value("Lan"));
        verify(geocoder, never()).placeDetail(any());

        String otherPlace = geocoder.autocomplete("Toà S3", null, null).get(2).placeId();
        send(patch("/api/account/addresses/" + id), Map.of("placeId", otherPlace))
                .andExpect(jsonPath("$.formattedAddress").value(org.hamcrest.Matchers.startsWith("Toà S3 (gợi ý 3)")));
        verify(geocoder).placeDetail(otherPlace);
    }

    @Test
    void anAddressNeedsAPickedSuggestion() throws Exception {
        Map<String, Object> body = body("Nhà", "fake:khong-co");
        send(post("/api/account/addresses"), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
        body.remove("placeId");
        send(post("/api/account/addresses"), body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void atMostTenAddresses() throws Exception {
        for (int i = 0; i < 10; i++) {
            create("Địa chỉ " + i, "Toà " + i, false);
        }
        send(post("/api/account/addresses"), body("Thứ 11", placeOf("Toà 11"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADDRESS_LIMIT_REACHED"));
    }

    @Test
    void addressesArePrivateAndCustomerOnly() throws Exception {
        String id = create("Nhà", "Toà riêng", false);
        String other = token(Role.CUSTOMER);
        mvc.perform(auth(get("/api/account/addresses"), other)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(delete("/api/account/addresses/" + id), other)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/account/addresses"), token(Role.SELLER))).andExpect(status().isForbidden());
    }

    // --- helpers

    private String create(String label, String place, boolean makeDefault) throws Exception {
        Map<String, Object> body = body(label, placeOf(place));
        body.put("makeDefault", makeDefault);
        String json = send(post("/api/account/addresses"), body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.id");
    }

    private String placeOf(String text) {
        return geocoder.autocomplete(text, null, null).get(0).placeId();
    }

    private static Map<String, Object> body(String label, String placeId) {
        Map<String, Object> body = new HashMap<>(Map.of("label", label, "placeId", placeId, "recipientName", "Lan Anh",
                "recipientPhone", "0912345678"));
        body.put("detail", "Tầng 5");
        return body;
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Map<String, Object> body) throws Exception {
        return mvc.perform(auth(request, customer).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(Role role) {
        User u = new User("addr-" + System.nanoTime() + "@example.com", null, "Khách");
        u.addRole(role);
        u.markEmailVerified();
        return tokens.issue(users.saveAndFlush(u), role, Instant.now()).accessToken();
    }
}
