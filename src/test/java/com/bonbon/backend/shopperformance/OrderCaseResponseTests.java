package com.bonbon.backend.shopperformance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.common.settings.SystemSettingsService;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.shopperformance.service.ShopCaseService;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.order.service.OrderTimers;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A shop answering a customer's case: accepting, disputing, running out of time, and the money each does (backend#100). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderCaseResponseTests {

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
    OrderTimers timers;

    @Autowired
    SystemSettingsService systemSettings;

    @Autowired
    ShopCaseService shopCases;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

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
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Đối Soát", "phone", "0912345678", "email", "q@example.com", "placeId", place));
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
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
    }

    @Test
    void theShopAcceptingARefundedOnlineOrderMovesTheMoneyOnceAndReleasesTheHold() throws Exception {
        String order = deliverByShop(paidOrder(2));       // 90.000 paid with MoMo
        int commission = jdbc.sql("select commission_amount from orders where id = :o::uuid").param("o", order).query(Integer.class).single();
        long before = balance();
        String id = file(order);

        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UPHELD"))
                .andExpect(jsonPath("$.decidedBy").value("SHOP")).andExpect(jsonPath("$.shopBears").value(90_000 - commission));

        assertThat(balance()).isEqualTo(before - 90_000 + commission);
        assertThat(ledgerAmount(id, "CASE_REFUND")).isEqualTo(-90_000);
        assertThat(ledgerAmount(id, "CASE_COMMISSION_REVERSAL")).isEqualTo(commission);
        assertThat(held()).isZero();
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isFalse();
        var refund = jdbc.sql("select amount, mode, status, reason from payment_refunds where case_id = :c::uuid").param("c", id).query().singleRow();
        assertThat(refund).containsEntry("amount", 90_000).containsEntry("mode", "GATEWAY").containsEntry("status", "REQUESTED").containsEntry("reason", "CASE_UPHELD");
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_UPHELD")).isEqualTo(1);
        call(customer, get("/api/orders/" + order + "/case"), null).andExpect(jsonPath("$.status").value("UPHELD")).andExpect(jsonPath("$.decidedBy").value("SHOP"))
                .andExpect(jsonPath("$.shopBears").doesNotExist()).andExpect(jsonPath("$.customerName").doesNotExist());

        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_ANSWERED"));
        assertThat(balance()).isEqualTo(before - 90_000 + commission);
    }

    @Test
    void aCashOrderIsRefundedByBankTransferAndTheShopOwesTheAmount() throws Exception {
        String order = deliverByShop(placeCod(2));
        int commission = jdbc.sql("select commission_amount from orders where id = :o::uuid").param("o", order).query(Integer.class).single();
        long before = balance();                          // already owes the commission of the cash order
        String id = file(order);
        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isOk());

        assertThat(balance()).isEqualTo(before - 90_000 + commission);
        var refund = jdbc.sql("select amount, mode, status from payment_refunds where case_id = :c::uuid").param("c", id).query().singleRow();
        assertThat(refund).containsEntry("amount", 90_000).containsEntry("mode", "MANUAL").containsEntry("status", "NEEDS_DESTINATION");
        assertThat(notificationsTo(customerId, "CUSTOMER", "REFUND_NEEDS_ACCOUNT")).isEqualTo(1);
    }

    @Test
    void aPartialIncidentRefundsOnlyTheClaimedPart() throws Exception {
        String order = deliverByShop(placeCod(2));
        String item = itemOf(order);
        String id = JsonPath.read(call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", item, "quantity", 1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(40_000));
        assertThat(ledgerAmount(id, "CASE_REFUND")).isEqualTo(-40_000);
        assertThat(jdbc.sql("select amount from payment_refunds where case_id = :c::uuid").param("c", id).query(Integer.class).single()).isEqualTo(40_000);
    }

    @Test
    void disputingSendsTheCaseToTheAdministratorsAndKeepsTheMoneyHeld() throws Exception {
        String order = deliverByShop(paidOrder(1));
        String id = file(order);
        long heldBefore = held();
        call(seller, post("/api/merchant/order-cases/" + id + "/dispute"), Map.of("note", " ")).andExpect(status().isBadRequest());
        call(seller, post("/api/merchant/order-cases/" + id + "/dispute"), Map.of("note", "Khách đã nhận, có người ký")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.shopResponse").value("DISPUTED"))
                .andExpect(jsonPath("$.shopResponseNote").value("Khách đã nhận, có người ký"));
        assertThat(held()).isEqualTo(heldBefore).isPositive();
        assertThat(jdbc.sql("select count(*) from ledger_entries where case_id = :c::uuid").param("c", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from payment_refunds where case_id = :c::uuid").param("c", id).query(Long.class).single()).isZero();
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_ESCALATED")).isEqualTo(1);

        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_ANSWERED"));
        call(seller, post("/api/merchant/order-cases/" + id + "/dispute"), Map.of("note", "Lại")).andExpect(status().isConflict());
    }

    @Test
    void aCaseTheShopNeverAnswersMovesToTheAdministratorsAfterTheDeadlineAndIsNeverDecided() throws Exception {
        String order = deliverByShop(placeCod(1));
        String id = file(order);
        Instant due = jdbc.sql("select shop_response_due_at from order_cases where id = :c::uuid").param("c", id).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();

        // The database is shared with other tests, so the counts are not ours to assert: only what happens to this case.
        shopCases.escalateOverdue(due.minusSeconds(60));
        assertThat(caseStatus(id)).isEqualTo("AWAITING_SHOP");
        assertThat(shopCases.escalateOverdue(due.plusSeconds(60))).isGreaterThanOrEqualTo(1);
        shopCases.escalateOverdue(due.plusSeconds(120));       // a second run changes nothing for this case
        assertThat(caseStatus(id)).isEqualTo("OPEN");
        assertThat(jdbc.sql("select shop_response from order_cases where id = :c::uuid").param("c", id).query(String.class).optional()).isEmpty();
        assertThat(held()).isPositive();
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_ESCALATED")).isEqualTo(1);
        call(seller, post("/api/merchant/order-cases/" + id + "/accept"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_ANSWERED"));
    }

    @Test
    void aShopOnlySeesAndAnswersItsOwnCasesAndTheListPutsWhatWaitsFirst() throws Exception {
        String waiting = file(deliverByShop(placeCod(1)));
        String decided = file(deliverByShop(placeCod(1)));
        call(seller, post("/api/merchant/order-cases/" + decided + "/accept"), null).andExpect(status().isOk());

        call(seller, get("/api/merchant/order-cases"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(waiting)).andExpect(jsonPath("$.items[0].status").value("AWAITING_SHOP"))
                .andExpect(jsonPath("$.items[1].id").value(decided)).andExpect(jsonPath("$.total").value(2));
        call(seller, get("/api/merchant/order-cases?status=UPHELD"), null).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(decided));
        call(seller, get("/api/merchant/order-cases?status=NOPE"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(seller, get("/api/merchant/order-cases/" + waiting), null).andExpect(status().isOk()).andExpect(jsonPath("$.customerName").value("Trần Văn An"))
                .andExpect(jsonPath("$.lines.length()").value(1));

        UUID otherOwner = newUser("seller2", Role.SELLER);
        String other = tokenOf(otherOwner, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(other, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherOwner).update();
        call(other, get("/api/merchant/order-cases"), null).andExpect(jsonPath("$.items.length()").value(0));
        call(other, get("/api/merchant/order-cases/" + waiting), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
        call(other, post("/api/merchant/order-cases/" + waiting + "/accept"), null).andExpect(status().isNotFound());
        call(other, post("/api/merchant/order-cases/" + waiting + "/dispute"), Map.of("note", "x")).andExpect(status().isNotFound());
        assertThat(caseStatus(waiting)).isEqualTo("AWAITING_SHOP");
    }

    @Test
    void onlyAShopWithAnApprovedShopCanAnswer() throws Exception {
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        call(fresh, get("/api/merchant/order-cases"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        call(customer, get("/api/merchant/order-cases"), null).andExpect(status().isForbidden());
        call(admin, get("/api/merchant/order-cases"), null).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/order-cases")).andExpect(status().isUnauthorized());
    }

    // --- response helpers

    private String file(String order) throws Exception {
        return JsonPath.read(call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private long balance() {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    private long held() {
        return jdbc.sql("select coalesce(sum(amount), 0) from settlement_case_holds where vendor_id = :v and released_at is null").param("v", vendorId).query(Long.class).single();
    }

    private int ledgerAmount(String caseId, String type) {
        return jdbc.sql("select amount from ledger_entries where case_id = :c::uuid and type = :t").param("c", caseId).param("t", type).query(Integer.class).single();
    }

    private String caseStatus(String caseId) {
        return jdbc.sql("select status from order_cases where id = :c::uuid").param("c", caseId).query(String.class).single();
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    /** The shop marks it delivered, so the customer has not confirmed anything. */
    private String deliverByShop(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
        return id;
    }

    private String itemOf(String orderId) {
        return jdbc.sql("select id from order_items where order_id = :o::uuid order by position limit 1").param("o", orderId).query(UUID.class).single().toString();
    }

    private int notificationsTo(UUID recipient, String audience, String type) {
        return jdbc.sql("select count(*) from notifications where recipient_id = :r and audience = :a and type = :t").param("r", recipient).param("a", audience).param("t", type)
                .query(Integer.class).single();
    }

    // --- scenario helpers

    private String placeCod(int quantity) throws Exception {
        return placeMixed(Map.of(dishId, quantity));
    }

    private String placeMixed(Map<String, Integer> quantities) throws Exception {
        keySeq++;
        List<Map<String, Object>> items = new ArrayList<>();
        quantities.forEach((dish, qty) -> items.add(Map.of("menuItemId", dish, "quantity", qty)));
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", items);
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-stats-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String paidOrder(int quantity) throws Exception {
        String id = placeOnline(quantity);
        ipn(providerOrderId(id), 40000L * quantity + 10000, 0).andExpect(status().isNoContent());
        return id;
    }

    private void deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk());
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
