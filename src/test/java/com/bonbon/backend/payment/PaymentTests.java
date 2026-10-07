package com.bonbon.backend.payment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.order.service.OrderTimers;
import com.bonbon.backend.payment.gateway.FakePaymentGateway;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import com.bonbon.backend.payment.service.PaymentReconciliation;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Online payment with MoMo, end to end against the fake gateway: place, IPN, retry, timeout, late success, reconciliation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PaymentTests {

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
    MomoSigner signer;

    @Autowired
    MomoSettings settings;

    @Autowired
    PaymentGateway gateway;

    @Autowired
    OrderTimers timers;

    @Autowired
    PaymentReconciliation reconciliation;

    String seller;
    UUID vendorId;
    String dishId;
    String customer;
    UUID customerId;
    String addressId;
    int keySeq;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;

        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Thanh Toán", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm sườn",
                "price", 40000, "stockQuantity", 50)).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");

        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Trần Văn An",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void anOnlineOrderWaitsForPaymentAndTheShopDoesNotSeeItYet() throws Exception {
        int before = stock();
        String id = placeOnline(2);
        call(customer, get("/api/orders/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paymentMethod").value("ONLINE")).andExpect(jsonPath("$.payment.attempt").value(1))
                .andExpect(jsonPath("$.payment.attemptStatus").value("PENDING")).andExpect(jsonPath("$.payment.payUrl").exists())
                .andExpect(jsonPath("$.payment.deeplink").exists()).andExpect(jsonPath("$.payment.expiresAt").exists());
        assertThat(stock()).isEqualTo(before - 2);
        assertThat(jdbc.sql("select method || ':' || status || ':' || amount from payments where order_id = :o::uuid").param("o", id).query(String.class).single())
                .isEqualTo("ONLINE:PENDING:90000");
        call(seller, get("/api/merchant/orders"), null).andExpect(jsonPath("$.total").value(0));
        call(seller, get("/api/merchant/orders/" + id), null).andExpect(status().isNotFound());
        assertThat(notifications("SHOP")).isZero();
    }

    @Test
    void theSignedIpnPaysTheOrderOnceAndStartsTheShopClock() throws Exception {
        String id = placeOnline(1);
        Instant created = placedAt(id);
        Thread.sleep(20);

        ipn(providerOrderId(id), 50000, 0).andExpect(status().isNoContent());

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PLACED")).andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.payment").doesNotExist());
        assertThat(placedAt(id)).isAfter(created);
        call(seller, get("/api/merchant/orders?status=PLACED"), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].responseDeadline").exists());
        assertThat(jdbc.sql("select status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("SUCCESS");
        assertThat(jdbc.sql("select status || ':' || provider_trans_id from payment_attempts where provider_order_id = :p").param("p", providerOrderId(id)).query(String.class).single())
                .startsWith("SUCCESS:");
        assertThat(notifications("SHOP")).isEqualTo(1);
        assertThat(notifications("CUSTOMER")).isEqualTo(1);

        // MoMo may repeat the notification: nothing changes.
        int steps = history(id);
        ipn(providerOrderId(id), 50000, 0).andExpect(status().isNoContent());
        assertThat(history(id)).isEqualTo(steps);
        assertThat(notifications("SHOP")).isEqualTo(1);
    }

    @Test
    void anIpnThatDoesNotCheckOutChangesNothing() throws Exception {
        String id = placeOnline(1);
        String po = providerOrderId(id);

        Map<String, Object> forged = signedIpn(po, 50000, 0);
        forged.put("signature", "00".repeat(32));
        postIpn(forged).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        Map<String, Object> changed = signedIpn(po, 50000, 0);
        changed.put("amount", 1000);
        postIpn(changed).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        Map<String, Object> otherPartner = signedIpn(po, 50000, 0, "OTHER");
        postIpn(otherPartner).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARTNER"));

        ipn(po, 1000, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
        ipn(UUID.randomUUID().toString(), 50000, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        // Coming back to the redirect page proves nothing: the order only moves on the signed IPN.
        mvc.perform(post("/api/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content("{\"resultCode\":0,\"orderId\":\"" + po + "\"}"))
                .andExpect(status().isBadRequest());
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void aDeclinedPaymentCanBeRetriedWithAFreshAttempt() throws Exception {
        String id = placeOnline(1);
        String first = providerOrderId(id);
        ipn(first, 50000, 1006).andExpect(status().isNoContent());
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.attemptStatus").value("FAILED"));

        call(customer, post("/api/orders/" + id + "/pay"), null).andExpect(status().isOk()).andExpect(jsonPath("$.payment.attempt").value(2))
                .andExpect(jsonPath("$.payment.attemptStatus").value("PENDING")).andExpect(jsonPath("$.payment.payUrl").exists());
        String second = providerOrderId(id);
        assertThat(second).isNotEqualTo(first);

        ipn(second, 50000, 0).andExpect(status().isNoContent());
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PLACED"));
        // Paid: no more attempts.
        call(customer, post("/api/orders/" + id + "/pay"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_PENDING"));
    }

    @Test
    void aRefusedCreateLeavesAFailedAttemptTheCustomerCanRetry() throws Exception {
        ((FakePaymentGateway) gateway).failNextCreate();
        String id = placeOnline(1);
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.attemptStatus").value("FAILED")).andExpect(jsonPath("$.payment.payUrl").doesNotExist());
        call(customer, post("/api/orders/" + id + "/pay"), null).andExpect(status().isOk()).andExpect(jsonPath("$.payment.attemptStatus").value("PENDING"))
                .andExpect(jsonPath("$.payment.attempt").value(2));
    }

    @Test
    void anUnpaidOrderIsCancelledAfterTheWindowAndGivesTheStockBack() throws Exception {
        int before = stock();
        String id = placeOnline(3);
        assertThat(stock()).isEqualTo(before - 3);
        assertThat(timers.runOnce(Instant.now()).unpaid()).isZero();

        // Other tests of this class leave unpaid orders behind in the shared database, so count at least ours.
        assertThat(timers.runOnce(Instant.now().plusSeconds(16 * 60)).unpaid()).isGreaterThanOrEqualTo(1);

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.timeline[1].reason").value("Hết thời hạn thanh toán."));
        assertThat(stock()).isEqualTo(before);
        assertThat(jdbc.sql("select status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbc.sql("select status from payment_attempts where provider_order_id = :p").param("p", providerOrderId(id)).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(notifications("SHOP")).isZero();
        assertThat(notifications("CUSTOMER")).isEqualTo(1);
        call(customer, post("/api/orders/" + id + "/pay"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_PENDING"));
    }

    @Test
    void aPaymentThatArrivesAfterTheTimeoutIsRecordedAndRefundedNotRevived() throws Exception {
        String id = placeOnline(1);
        String po = providerOrderId(id);
        timers.runOnce(Instant.now().plusSeconds(16 * 60));

        ipn(po, 50000, 0).andExpect(status().isNoContent());

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.sql("select status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("SUCCESS");
        assertThat(jdbc.sql("select status from payment_attempts where provider_order_id = :p").param("p", po).query(String.class).single()).isEqualTo("SUCCESS");
        assertThat(jdbc.sql("""
                select r.reason || ':' || r.status || ':' || r.amount from payment_refunds r join payments p on p.id = r.payment_id
                where p.order_id = :o::uuid""").param("o", id).query(String.class).single()).isEqualTo("LATE_PAYMENT:REQUESTED:50000");
        assertThat(notifications("SHOP")).isZero();
        // A repeat does not queue a second refund.
        ipn(po, 50000, 0).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid")
                .param("o", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reconciliationFindsAPaymentWhoseIpnNeverCame() throws Exception {
        String id = placeOnline(1);
        String po = providerOrderId(id);
        // Nothing is decided yet and it is too early to ask.
        assertThat(reconciliation.runOnce(Instant.now())).isZero();
        assertThat(reconciliation.runOnce(Instant.now().plusSeconds(3 * 60))).isZero();
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        ((FakePaymentGateway) gateway).answer(po, new PaymentGateway.QueryResult(0, 123456789L, 50000, "qr", "Successful."));
        assertThat(reconciliation.runOnce(Instant.now().plusSeconds(3 * 60))).isEqualTo(1);

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PLACED")).andExpect(jsonPath("$.paymentStatus").value("PAID"));
        assertThat(jdbc.sql("select provider_trans_id from payment_attempts where provider_order_id = :p").param("p", po).query(Long.class).single()).isEqualTo(123456789L);
    }

    @Test
    void anAttemptStillUndecidedAfterItsDeadlineExpires() throws Exception {
        String id = placeOnline(1);
        String po = providerOrderId(id);
        assertThat(reconciliation.runOnce(Instant.now().plusSeconds(16 * 60))).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select status from payment_attempts where provider_order_id = :p").param("p", po).query(String.class).single()).isEqualTo("EXPIRED");

        // MoMo says it was denied: failed, not expired.
        String other = placeOnline(1);
        String po2 = providerOrderId(other);
        ((FakePaymentGateway) gateway).answer(po2, new PaymentGateway.QueryResult(1006, 0, 50000, null, "Denied"));
        assertThat(reconciliation.runOnce(Instant.now().plusSeconds(3 * 60))).isEqualTo(1);
        assertThat(jdbc.sql("select status from payment_attempts where provider_order_id = :p").param("p", po2).query(String.class).single()).isEqualTo("FAILED");
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void theCustomerCanAbandonAnUnpaidOrder() throws Exception {
        int before = stock();
        String id = placeOnline(2);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(stock()).isEqualTo(before);
        assertThat(jdbc.sql("select status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("FAILED");
        assertThat(notifications("SHOP")).isZero();
    }

    @Test
    void onlineAmountsOutsideMomosRangeAreRefused() throws Exception {
        jdbc.sql("update menu_items set price = 500 where id = :d::uuid").param("d", dishId).update();
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "ONLINE",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        // 500 + the 10.000 delivery fee is over the 1.000 minimum; push the total under it with free delivery instead.
        jdbc.sql("update vendors set delivery_fee = 0 where id = :v").param("v", vendorId).update();
        call(customer, post("/api/orders"), body, "key-pay-low-" + System.nanoTime()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONLINE_AMOUNT_OUT_OF_RANGE"));
        // The same order is fine at the door.
        Map<String, Object> cod = new LinkedHashMap<>(body);
        cod.put("paymentMethod", "COD");
        call(customer, post("/api/orders"), cod, "key-pay-cod-" + System.nanoTime()).andExpect(status().isCreated());
    }

    @Test
    void aCashOrderHasItsPaymentRowAndItIsPaidWhenDelivered() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        String id = JsonPath.read(call(customer, post("/api/orders"), body, "key-pay-cash-" + System.nanoTime()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(jdbc.sql("select method || ':' || status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("COD:PENDING");
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk());
        assertThat(jdbc.sql("select status from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("SUCCESS");
    }

    @Test
    void anotherCustomerCannotPayMyOrder() throws Exception {
        String id = placeOnline(1);
        String other = tokenOf(newUser("customer2", Role.CUSTOMER), Role.CUSTOMER);
        call(other, post("/api/orders/" + id + "/pay"), null).andExpect(status().isNotFound());
        call(seller, post("/api/orders/" + id + "/pay"), null).andExpect(status().isForbidden());
    }

    // --- helpers

    private int stock() {
        return jdbc.sql("select stock_quantity from menu_items where id = :d::uuid").param("d", dishId).query(Integer.class).single();
    }

    private Instant placedAt(String orderId) {
        return jdbc.sql("select placed_at from orders where id = :o::uuid").param("o", orderId).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
    }

    private int history(String orderId) {
        return jdbc.sql("select count(*) from order_status_history where order_id = :o::uuid").param("o", orderId).query(Integer.class).single();
    }

    private int notifications(String audience) {
        return jdbc.sql("select count(*) from notifications where audience = :a and recipient_id = :r")
                .param("a", audience).param("r", audience.equals("SHOP") ? sellerUserId() : customerId).query(Integer.class).single();
    }

    private UUID sellerUserId() {
        return jdbc.sql("select owner_user_id from vendors where id = :v").param("v", vendorId).query(UUID.class).single();
    }

    private String providerOrderId(String orderId) {
        return jdbc.sql("""
                select a.provider_order_id from payment_attempts a join payments p on p.id = a.payment_id
                where p.order_id = :o::uuid order by a.attempt_no desc limit 1""").param("o", orderId).query(String.class).single();
    }

    private String placeOnline(int quantity) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "ONLINE",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-pay-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private Map<String, Object> signedIpn(String providerOrderId, long amount, int resultCode) {
        return signedIpn(providerOrderId, amount, resultCode, settings.partnerCode());
    }

    /** What MoMo would post, signed with the configured keys. */
    private Map<String, Object> signedIpn(String providerOrderId, long amount, int resultCode, String partnerCode) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("amount", amount);
        fields.put("extraData", "");
        fields.put("message", resultCode == 0 ? "Successful." : "Declined");
        fields.put("orderId", providerOrderId);
        fields.put("orderInfo", "Thanh toan don");
        fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", partnerCode);
        fields.put("payType", "qr");
        fields.put("requestId", UUID.randomUUID().toString());
        fields.put("responseTime", 1_700_000_000_000L);
        fields.put("resultCode", resultCode);
        fields.put("transId", 4_000_000_001L);
        Map<String, Object> body = new LinkedHashMap<>(fields);
        body.put("signature", signer.sign(fields));
        return body;
    }

    private ResultActions ipn(String providerOrderId, long amount, int resultCode) throws Exception {
        return postIpn(signedIpn(providerOrderId, amount, resultCode));
    }

    private ResultActions postIpn(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/payments/momo/ipn")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private UUID newUser(String kind, Role role) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", null, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(Map.of("weekday", day, "opensAt", "00:00", "closesAt", "12:00"));
            windows.add(Map.of("weekday", day, "opensAt", "12:00", "closesAt", "00:00"));
        }
        return windows;
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        return call(token, request, body, null);
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body, String idempotencyKey) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
