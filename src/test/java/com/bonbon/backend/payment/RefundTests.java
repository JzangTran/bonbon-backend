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
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.payment.service.PaymentRefunds;
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

/** Giving money back: the MoMo path with its retry and refusal codes, and the manual bank-transfer queue. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RefundTests {

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
    PaymentRefunds refunds;

    @Autowired
    SystemSettingsService systemSettings;

    String admin;
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
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        ((FakePaymentGateway) gateway).answerRefunds(null);
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void aPaidOrderThatIsCancelledGetsItsMoneyBackThroughMomo() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("REQUESTED")).andExpect(jsonPath("$.refund.mode").value("GATEWAY"))
                .andExpect(jsonPath("$.refund.amount").value(50000));
        UUID refund = refundOf(id);
        int calls = fake().refundCalls().size();

        refunds.execute(refund);

        PaymentGateway.RefundRequest sent = fake().refundCalls().get(calls);
        assertThat(sent.providerOrderId()).isEqualTo(refund.toString());
        assertThat(sent.amount()).isEqualTo(50000);
        assertThat(sent.purchaseTransId()).isEqualTo(4_000_000_001L);
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.refund.status").value("COMPLETED"));
        assertThat(jdbc.sql("select status || ':' || refunded_amount from payments where order_id = :o::uuid").param("o", id).query(String.class).single())
                .isEqualTo("REFUNDED:50000");
        assertThat(notificationTypes(customerId)).contains("REFUND_DONE");
        // Submitting again changes nothing and does not call MoMo again.
        int after = fake().refundCalls().size();
        refunds.execute(refund);
        assertThat(fake().refundCalls()).hasSize(after);
    }

    @Test
    void aShopRejectingAPaidOrderRefundsItToo() throws Exception {
        String id = paidOrder(1);
        call(seller, post("/api/merchant/orders/" + id + "/reject"), Map.of("reason", "Hết món")).andExpect(status().isOk());
        assertThat(refundStatus(id)).isEqualTo("REQUESTED");
    }

    @Test
    void ordersThatWereNeverPaidOnlineOweNothingBack() throws Exception {
        String unpaid = placeOnline(1);
        call(customer, post("/api/orders/" + unpaid + "/cancel"), null).andExpect(status().isOk());
        assertThat(refundCount(unpaid)).isZero();

        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        String cod = JsonPath.read(call(customer, post("/api/orders"), body, "key-refund-cod-" + System.nanoTime()).andReturn().getResponse().getContentAsString(), "$.id");
        call(customer, post("/api/orders/" + cod + "/cancel"), null).andExpect(status().isOk()).andExpect(jsonPath("$.refund").doesNotExist());
        assertThat(refundCount(cod)).isZero();
    }

    @Test
    void aLatePaymentIsRefundedOnceItsOrderIsClosed() throws Exception {
        String id = placeOnline(1);
        String po = providerOrderId(id);
        timers.runOnce(Instant.now().plusSeconds(16 * 60));
        ipn(po, 50000, 0).andExpect(status().isNoContent());
        UUID refund = refundOf(id);
        refunds.execute(refund);
        assertThat(refundStatus(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("select reason from payment_refunds where id = :r").param("r", refund).query(String.class).single()).isEqualTo("LATE_PAYMENT");
    }

    @Test
    void momoAskingForALaterRetryKeepsTheRefundGoingUntilItWorksOrGivesUp() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk());
        UUID refund = refundOf(id);

        fake().answerRefunds(new PaymentGateway.RefundResult(1080, 0, "Retry in a short period"));
        refunds.execute(refund);
        assertThat(refundStatus(id)).isEqualTo("PROCESSING");
        int calls = fake().refundCalls().size();
        refunds.execute(refund);
        assertThat(fake().refundCalls()).as("held for a minute").hasSize(calls);

        // The hold passes and MoMo is ready: the same refund order id goes through.
        release(refund);
        fake().answerRefunds(null);
        refunds.execute(refund);
        assertThat(refundStatus(id)).isEqualTo("COMPLETED");
        assertThat(fake().refundCalls().stream().map(PaymentGateway.RefundRequest::providerOrderId).filter(refund.toString()::equals).count()).isEqualTo(2);

        // Another refund that never gets through ends up with a person after five tries.
        String other = paidOrder(1);
        call(customer, post("/api/orders/" + other + "/cancel"), null).andExpect(status().isOk());
        UUID stuck = refundOf(other);
        fake().answerRefunds(new PaymentGateway.RefundResult(1080, 0, "Retry in a short period"));
        for (int i = 0; i < 5; i++) {
            refunds.execute(stuck);
            release(stuck);
        }
        assertThat(refundStatus(other)).isEqualTo("NEEDS_DESTINATION");
        assertThat(jdbc.sql("select mode from payment_refunds where id = :r").param("r", stuck).query(String.class).single()).isEqualTo("MANUAL");
    }

    @Test
    void aRepeatedRefundOrderIdIsResolvedFromMomosOwnList() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk());
        UUID refund = refundOf(id);
        // MoMo already holds this refund (code 41); the payment's refund list says it went through.
        fake().answerRefund(refund.toString(), new PaymentGateway.RefundResult(41, 0, "duplicated orderId"));
        fake().answer(providerOrderId(id), new PaymentGateway.QueryResult(0, 4_000_000_001L, 50000, "qr", "ok",
                List.of(new PaymentGateway.RefundTrans(refund.toString(), 777L, 50000, 0))));

        refunds.execute(refund);

        assertThat(refundStatus(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("select provider_trans_id from payment_refunds where id = :r").param("r", refund).query(Long.class).single()).isEqualTo(777L);
    }

    @Test
    void aRefusedRefundBecomesABankTransferTheAdminCompletes() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk());
        UUID refund = refundOf(id);
        fake().answerRefunds(new PaymentGateway.RefundResult(1088, 0, "ineligible to be refunded"));

        refunds.execute(refund);

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.refund.status").value("NEEDS_DESTINATION")).andExpect(jsonPath("$.refund.mode").value("MANUAL"))
                .andExpect(jsonPath("$.refund.needsDestination").value(true));
        assertThat(jdbc.sql("select gateway_result_code from payment_refunds where id = :r").param("r", refund).query(Integer.class).single()).isEqualTo(1088);
        assertThat(notificationTypes(customerId)).contains("REFUND_NEEDS_ACCOUNT");

        // It is in the admin queue, waiting for the customer; the account number is not there yet.
        call(admin, get("/api/admin/refunds?status=NEEDS_DESTINATION&size=50"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + refund + "')].orderNumber").exists());
        // Nothing to complete until the customer gives an account.
        call(admin, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT0001")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_PAYABLE"));

        String other = tokenOf(newUser("customer2", Role.CUSTOMER), Role.CUSTOMER);
        Map<String, Object> account = Map.of("bankName", "Vietcombank", "accountNumber", "0123456789", "accountName", "NGUYEN VAN AN");
        call(other, put("/api/orders/" + id + "/refund-destination"), account).andExpect(status().isNotFound());
        call(seller, put("/api/orders/" + id + "/refund-destination"), account).andExpect(status().isForbidden());
        call(customer, put("/api/orders/" + id + "/refund-destination"), Map.of("bankName", "Vietcombank", "accountNumber", "12ab", "accountName", "A")).andExpect(status().isBadRequest());
        call(customer, put("/api/orders/" + id + "/refund-destination"), account).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.destinationLast4").value("6789"));
        call(customer, put("/api/orders/" + id + "/refund-destination"), account).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ACCEPTING_DESTINATION"));
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.refund.destinationLast4").value("6789"))
                .andExpect(jsonPath("$.refund.needsDestination").value(false));
        // The number is stored encrypted.
        assertThat(jdbc.sql("select destination_number from payment_refunds where id = :r").param("r", refund).query(String.class).single()).doesNotContain("0123456789");

        call(admin, get("/api/admin/refunds?size=50"), null).andExpect(jsonPath("$.items[?(@.id == '" + refund + "')].destination.accountNumber").value("0123456789"))
                .andExpect(jsonPath("$.items[?(@.id == '" + refund + "')].status").value("REQUESTED"));
        call(seller, get("/api/admin/refunds"), null).andExpect(status().isForbidden());
        call(customer, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT1")).andExpect(status().isForbidden());

        call(admin, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT2610051234")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.bankReference").value("FT2610051234"));
        call(admin, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT2610059999")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_PAYABLE"));
        assertThat(jdbc.sql("select status || ':' || refunded_amount from payments where order_id = :o::uuid").param("o", id).query(String.class).single()).isEqualTo("REFUNDED:50000");
        assertThat(notificationTypes(customerId)).contains("REFUND_DONE");
        assertThat(jdbc.sql("select count(*) from payment_refund_log where refund_id = :r and actor_type = 'ADMIN'").param("r", refund).query(Long.class).single()).isEqualTo(1);
        call(admin, post("/api/admin/refunds/" + UUID.randomUUID() + "/complete"), Map.of("bankReference", "FT0002")).andExpect(status().isNotFound());
    }

    @Test
    void aBankReferenceCannotSettleTwoRefunds() throws Exception {
        UUID first = manualRefundWithAccount();
        UUID second = manualRefundWithAccount();
        call(admin, post("/api/admin/refunds/" + first + "/complete"), Map.of("bankReference", "SAME-REF-001")).andExpect(status().isOk());
        call(admin, post("/api/admin/refunds/" + second + "/complete"), Map.of("bankReference", "same-ref-001")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BANK_REFERENCE"));
        assertThat(jdbc.sql("select status from payment_refunds where id = :r").param("r", second).query(String.class).single()).isEqualTo("REQUESTED");
    }

    @Test
    void aFailedTransferAsksTheCustomerForAnotherAccount() throws Exception {
        UUID refund = manualRefundWithAccount();
        String orderId = jdbc.sql("select p.order_id::text from payment_refunds r join payments p on p.id = r.payment_id where r.id = :r").param("r", refund).query(String.class).single();
        call(admin, post("/api/admin/refunds/" + refund + "/fail"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/refunds/" + refund + "/fail"), Map.of("reason", "Tài khoản đã đóng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_DESTINATION")).andExpect(jsonPath("$.failureReason").value("Tài khoản đã đóng"))
                .andExpect(jsonPath("$.destination").doesNotExist());
        call(customer, get("/api/orders/" + orderId), null).andExpect(jsonPath("$.refund.needsDestination").value(true))
                .andExpect(jsonPath("$.refund.failureReason").value("Tài khoản đã đóng"));
        call(customer, put("/api/orders/" + orderId + "/refund-destination"), Map.of("bankName", "Techcombank", "accountNumber", "9876543210", "accountName", "TRAN B"))
                .andExpect(status().isOk());
        call(admin, post("/api/admin/refunds/" + refund + "/complete"), Map.of("bankReference", "FT-OK-0042")).andExpect(status().isOk());
    }

    @Test
    void switchingTheRefundApiOffSendsEveryRefundToTheDesk() throws Exception {
        systemSettings.set("payment.refund_mode", "MANUAL", ActorType.SYSTEM, null);
        try {
            String id = paidOrder(1);
            int calls = fake().refundCalls().size();
            call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk()).andExpect(jsonPath("$.refund.status").value("NEEDS_DESTINATION"))
                    .andExpect(jsonPath("$.refund.mode").value("MANUAL"));
            assertThat(fake().refundCalls()).hasSize(calls);
            assertThat(notificationTypes(customerId)).contains("REFUND_NEEDS_ACCOUNT");
        } finally {
            systemSettings.set("payment.refund_mode", "GATEWAY", ActorType.SYSTEM, null);
        }
    }

    @Test
    void theQueueListsEveryRefundForTheMonthEndSheet() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk());
        refunds.execute(refundOf(id));
        call(admin, get("/api/admin/refunds?status=ALL&size=50"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber());
        call(admin, get("/api/admin/refunds?status=COMPLETED"), null).andExpect(status().isOk());
        call(admin, get("/api/admin/refunds?status=BOGUS"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(admin, get("/api/admin/refunds?size=500"), null).andExpect(status().isBadRequest());
    }

    // --- scenario helpers

    private FakePaymentGateway fake() {
        return (FakePaymentGateway) gateway;
    }

    /** An online order the customer has paid (a signed IPN), now waiting for the shop. */
    private String paidOrder(int quantity) throws Exception {
        String id = placeOnline(quantity);
        ipn(providerOrderId(id), 40000L * quantity + 10000, 0).andExpect(status().isNoContent());
        return id;
    }

    /** A refund in the manual queue with the customer's account already given, ready for the admin. */
    private UUID manualRefundWithAccount() throws Exception {
        String id = paidOrder(1);
        call(customer, post("/api/orders/" + id + "/cancel"), null).andExpect(status().isOk());
        UUID refund = refundOf(id);
        fake().answerRefunds(new PaymentGateway.RefundResult(1081, 0, "might have been refunded"));
        refunds.execute(refund);
        fake().answerRefunds(null);
        call(customer, put("/api/orders/" + id + "/refund-destination"), Map.of("bankName", "Vietcombank", "accountNumber", "0123456789", "accountName", "NGUYEN VAN AN"))
                .andExpect(status().isOk());
        return refund;
    }

    private UUID refundOf(String orderId) {
        return jdbc.sql("select r.id from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid order by r.created_at desc limit 1")
                .param("o", orderId).query(UUID.class).single();
    }

    private String refundStatus(String orderId) {
        return jdbc.sql("select r.status from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid order by r.created_at desc limit 1")
                .param("o", orderId).query(String.class).single();
    }

    private long refundCount(String orderId) {
        return jdbc.sql("select count(*) from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid").param("o", orderId).query(Long.class).single();
    }

    private void release(UUID refund) {
        jdbc.sql("update payment_refunds set claimed_until = now() - interval '1 second' where id = :r").param("r", refund).update();
    }

    private List<String> notificationTypes(UUID recipient) {
        return jdbc.sql("select type from notifications where recipient_id = :r").param("r", recipient).query(String.class).list();
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
