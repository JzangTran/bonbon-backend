package com.bonbon.backend.ordering.order;

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

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paying by bank transfer (VietQR): the order goes straight to the shop, the customer gets the shop's account and the code, and
 * the shop marks the money received. A shop without a known bank does not take transfers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BankTransferTests {

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
        call(seller, put("/api/merchant/shop/steps/1"), com.bonbon.backend.ShopAddress.step1("Quán Chuyển Khoản", "0912345678", "ck@example.com"));
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
    void theCustomerGetsTheShopsAccountAndCodeAndTheShopMarksTheMoneyReceived() throws Exception {
        receivingAccount("Vietcombank", "0011001234567");
        String order = call(customer, post("/api/orders"), order("BANK_TRANSFER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLACED"))
                .andExpect(jsonPath("$.paymentMethod").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(order, "$.id");
        long number = ((Number) JsonPath.read(order, "$.number")).longValue();

        call(customer, get("/api/orders/" + id + "/bank-transfer"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("Vietcombank"))
                .andExpect(jsonPath("$.bankBin").value("970436"))
                .andExpect(jsonPath("$.accountNumber").value("0011001234567"))
                .andExpect(jsonPath("$.amount").value(55000))
                .andExpect(jsonPath("$.message").value("BONBON" + number))
                .andExpect(jsonPath("$.qrPayload").value(startsWith("000201010212")))
                .andExpect(jsonPath("$.received").value(false));

        call(seller, post("/api/merchant/orders/" + id + "/transfer-received"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        call(seller, post("/api/merchant/orders/" + id + "/transfer-received"), null).andExpect(status().isConflict());
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null).andExpect(jsonPath("$.received").value(true));
    }

    @Test
    void aShopWithoutAKnownBankTakesNoTransfers() throws Exception {
        call(customer, post("/api/orders"), order("BANK_TRANSFER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANK_TRANSFER_UNAVAILABLE"));
        receivingAccount("Ngân hàng tự đặt", "123456");
        call(customer, post("/api/orders"), order("BANK_TRANSFER"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANK_TRANSFER_UNAVAILABLE"));
    }

    @Test
    void aCashOrderHasNoTransferAndCannotBeMarkedReceived() throws Exception {
        receivingAccount("Vietcombank", "0011001234567");
        String id = JsonPath.read(call(customer, post("/api/orders"), order("COD")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(customer, get("/api/orders/" + id + "/bank-transfer"), null).andExpect(jsonPath("$.code").value("NOT_A_BANK_TRANSFER"));
        call(seller, post("/api/merchant/orders/" + id + "/transfer-received"), null).andExpect(jsonPath("$.code").value("NOT_A_BANK_TRANSFER"));
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
