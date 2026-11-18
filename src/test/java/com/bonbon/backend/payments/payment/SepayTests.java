package com.bonbon.backend.payments.payment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.crypto.FieldCipher;
import com.bonbon.backend.common.geo.Geocoder;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Automatic transfer confirmation (SePay): the shop gets a webhook key once, SePay reports each transaction with it, and a transfer
 * carrying BONBON&lt;number&gt; that covers the total marks the order paid. Wrong keys are refused, repeats are ignored.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SepayTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    Geocoder geocoder;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FieldCipher cipher;

    final double lng = 151.4;
    double lat;
    String seller;
    UUID vendorId;
    String dishId;
    String customer;
    String addressId;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        lat = -79 + (System.nanoTime() % 1000) * 0.01;
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1("Quán SePay", "0912345679", "sepay@example.com"));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm gà", "price", 45000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");

        UUID customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void aTransferWithTheCodeAndFullAmountMarksTheOrderPaidOnce() throws Exception {
        receivingAccount("Vietcombank", "0011001234567");
        String key = connect();
        String order = call(customer, post("/api/orders"), order("BANK_TRANSFER")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(order, "$.id");
        long number = ((Number) JsonPath.read(order, "$.number")).longValue();

        long sepayId = System.nanoTime();
        hook(key, Map.of("id", sepayId, "gateway", "Vietcombank", "accountNumber", "0011001234567", "content", "MBVCB.123 bonbon " + number + " chuyen tien",
                "transferType", "in", "transferAmount", 55000)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null).andExpect(jsonPath("$.received").value(true));

        hook(key, Map.of("id", sepayId, "content", "BONBON" + number, "transferType", "in", "transferAmount", 55000)).andExpect(status().isOk());
        call(seller, get("/api/merchant/payments/sepay"), null)
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.webhookUrl").value(endsWith("/api/payments/sepay/" + vendorId)))
                .andExpect(jsonPath("$.recent.length()").value(1))
                .andExpect(jsonPath("$.recent[0].outcome").value("PAID"))
                .andExpect(jsonPath("$.recent[0].orderNumber").value(number));
    }

    @Test
    void aShortOrUnmatchedTransferLeavesTheOrderUnpaid() throws Exception {
        receivingAccount("Vietcombank", "0011001234567");
        String key = connect();
        String order = call(customer, post("/api/orders"), order("BANK_TRANSFER")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(order, "$.id");
        long number = ((Number) JsonPath.read(order, "$.number")).longValue();

        hook(key, Map.of("id", System.nanoTime(), "content", "BONBON" + number, "transferType", "in", "transferAmount", 50000)).andExpect(status().isOk());
        hook(key, Map.of("id", System.nanoTime(), "content", "tra tien com", "transferType", "in", "transferAmount", 55000)).andExpect(status().isOk());
        hook(key, Map.of("id", System.nanoTime(), "content", "BONBON" + (number + 99999), "transferType", "in", "transferAmount", 55000)).andExpect(status().isOk());
        hook(key, Map.of("id", System.nanoTime(), "content", "BONBON" + number, "transferType", "out", "transferAmount", 55000)).andExpect(status().isOk());
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null).andExpect(jsonPath("$.received").value(false));
        call(seller, get("/api/merchant/payments/sepay"), null)
                .andExpect(jsonPath("$.recent[3].outcome").value("AMOUNT_SHORT"))
                .andExpect(jsonPath("$.recent[2].outcome").value("NO_CODE"))
                .andExpect(jsonPath("$.recent[1].outcome").value("NOT_FOUND"))
                .andExpect(jsonPath("$.recent[0].outcome").value("OUTGOING"));
    }

    @Test
    void aWrongOrOldKeyIsRefusedAndDisconnectingStopsTheFeed() throws Exception {
        String old = connect();
        String key = connect();
        Map<String, Object> body = Map.of("id", System.nanoTime(), "content", "BONBON1", "transferType", "in", "transferAmount", 1000);
        hook(old, body).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SEPAY_UNAUTHORIZED"));
        hook(null, body).andExpect(status().isUnauthorized());
        hook(key, body).andExpect(status().isOk());
        call(seller, delete("/api/merchant/payments/sepay"), null).andExpect(status().isNoContent());
        hook(key, body).andExpect(status().isUnauthorized());
        call(seller, get("/api/merchant/payments/sepay"), null).andExpect(jsonPath("$.connected").value(false));
        call(customer, get("/api/merchant/payments/sepay"), null).andExpect(status().isForbidden());
    }

    @Test
    void aSubAccountCodeGoesInFrontOfTheTransferContentAndStillMatches() throws Exception {
        receivingAccount("VietinBank", "0011001234567");
        call(seller, put("/api/merchant/payments/sepay/sub-account"), Map.of("code", "2BO")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEPAY_NOT_CONNECTED"));
        String key = connect();
        call(seller, put("/api/merchant/payments/sepay/sub-account"), Map.of("code", "2b o")).andExpect(status().isBadRequest());
        call(seller, put("/api/merchant/payments/sepay/sub-account"), Map.of("code", " tkp2bo ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subAccount").value("2BO"));
        String order = call(customer, post("/api/orders"), order("BANK_TRANSFER")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(order, "$.id");
        long number = ((Number) JsonPath.read(order, "$.number")).longValue();
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null)
                .andExpect(jsonPath("$.message").value("SEVQR TKP2BO BONBON" + number));

        hook(key, Map.of("id", System.nanoTime(), "content", "SEVQR TKP2BO BONBON" + number, "transferType", "in", "transferAmount", 55000))
                .andExpect(status().isOk());
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null).andExpect(jsonPath("$.received").value(true));

        call(seller, put("/api/merchant/payments/sepay/sub-account"), Map.of("code", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.subAccount").doesNotExist());
    }

    private String connect() throws Exception {
        return JsonPath.read(call(seller, post("/api/merchant/payments/sepay"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(startsWith("bb_"))).andReturn().getResponse().getContentAsString(), "$.apiKey");
    }

    private ResultActions hook(String key, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/payments/sepay/" + vendorId).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        if (key != null) {
            request.header("Authorization", "Apikey " + key);
        }
        return mvc.perform(request);
    }

    private void receivingAccount(String bank, String account) {
        jdbc.sql("delete from marketplace.vendor_payout_accounts where vendor_id = :v").param("v", vendorId).update();
        jdbc.sql("""
                insert into marketplace.vendor_payout_accounts (vendor_id, bank_name, account_number_encrypted, account_last4, account_holder_name, created_by_type)
                values (:v, :b, :n, :l, 'NGUYEN VAN A', 'SYSTEM')""")
                .param("v", vendorId).param("b", bank).param("n", cipher.encrypt(account)).param("l", account.substring(account.length() - 4)).update();
    }

    private Map<String, Object> order(String payment) {
        return Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", payment,
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1, "optionIds", List.of())));
    }

    private UUID newUser(String kind, Role role) {
        User user = new User(kind + "-ck-" + System.nanoTime() + "@example.com", null, "Người dùng");
        user.addRole(role);
        user.markEmailVerified();
        return users.saveAndFlush(user).getId();
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new java.util.ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(Map.of("weekday", day, "opensAt", "00:00", "closesAt", "12:00"));
            windows.add(Map.of("weekday", day, "opensAt", "12:00", "closesAt", "00:00"));
        }
        return windows;
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
