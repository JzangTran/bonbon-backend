package com.bonbon.backend.settlement;

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
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.settlement.service.LedgerService;
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

/** The shop ledger: what an ended order posts, when, and that a posted entry can never change (backend#84). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LedgerTests {

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
    LedgerService ledger;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    PlatformTransactionManager transactionManager;

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
    void aCashOrderChargesTheShopItsCommissionOnlyWhenDelivered() throws Exception {
        String id = placeCod(1);
        assertThat(ledger.entries(vendorId, 0, 50)).isEmpty();
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        assertThat(ledger.entries(vendorId, 0, 50)).as("nothing before the order ends").isEmpty();

        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk());

        List<LedgerService.Entry> entries = ledger.entries(vendorId, 0, 50);
        assertThat(entries).hasSize(1);
        LedgerService.Entry entry = entries.get(0);
        assertThat(entry.type()).isEqualTo("COD_COMMISSION");
        assertThat(entry.amount()).isEqualTo(-4000); // 10 % of the 40.000 food value; the delivery fee is not commissioned
        assertThat(entry.orderId().toString()).isEqualTo(id);
        assertThat(entry.actedByType()).isEqualTo("SYSTEM");
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(-4000);
    }

    @Test
    void anOnlineOrderCreditsTheShopWhatTheCustomerPaidMinusTheCommission() throws Exception {
        String id = paidOrder(2);
        assertThat(ledger.entries(vendorId, 0, 50)).as("paying posts nothing").isEmpty();
        deliver(id);

        List<LedgerService.Entry> entries = ledger.entries(vendorId, 0, 50);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).type()).isEqualTo("ONLINE_EARNING");
        // 2 x 40.000 food + 10.000 delivery = 90.000 paid; 10 % of the 80.000 food is the 8.000 commission.
        assertThat(entries.get(0).amount()).isEqualTo(82_000);
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(82_000);
    }

    @Test
    void theBalanceIsTheSumOfEntriesAndItsSignSaysWhoOwesWhom() throws Exception {
        deliver(paidOrder(1));      // +45.000: the platform holds 50.000 and owes the shop 50.000 - 4.000 + ... see below
        String cash = placeCod(1);
        call(seller, post("/api/merchant/orders/" + cash + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + cash + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + cash + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        call(customer, post("/api/orders/" + cash + "/received"), null).andExpect(status().isOk());

        long sum = ledger.entries(vendorId, 0, 50).stream().mapToLong(LedgerService.Entry::amount).sum();
        assertThat(ledger.entries(vendorId, 0, 50)).hasSize(2);
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(sum).isEqualTo(46_000 - 4_000);
    }

    @Test
    void ordersThatNeverEndDeliveredPostNothing() throws Exception {
        String cancelled = paidOrder(1);
        call(customer, post("/api/orders/" + cancelled + "/cancel"), null).andExpect(status().isOk());
        String rejected = paidOrder(1);
        call(seller, post("/api/merchant/orders/" + rejected + "/reject"), Map.of("reason", "Hết món")).andExpect(status().isOk());
        String unpaid = placeOnline(1);
        call(customer, post("/api/orders/" + unpaid + "/cancel"), null).andExpect(status().isOk());
        assertThat(ledger.entries(vendorId, 0, 50)).isEmpty();
        assertThat(ledger.balanceOf(vendorId)).isZero();
    }

    @Test
    void anOrderTheSystemDeliversAfterTheTimeoutPostsToo() throws Exception {
        String id = paidOrder(1);
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null);
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING"));
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY"));
        timers.runOnce(Instant.now().plusSeconds(4 * 3600));
        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(ledger.entries(vendorId, 0, 50)).extracting(LedgerService.Entry::type).containsExactly("ONLINE_EARNING");
    }

    @Test
    void aCustomerNoShowPaysTheShopOnlineButNotForCash() throws Exception {
        String online = paidOrder(1);
        String cash = placeCod(1);
        endAs(online, OrderStatus.NOT_DELIVERED);
        endAs(cash, OrderStatus.NOT_DELIVERED);
        assertThat(ledger.entries(vendorId, 0, 50)).extracting(LedgerService.Entry::type, LedgerService.Entry::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ONLINE_EARNING", 46_000));
    }

    @Test
    void repeatingTheEventNeverPostsTwice() throws Exception {
        String id = paidOrder(1);
        deliver(id);
        endAs(id, OrderStatus.DELIVERED);
        endAs(id, OrderStatus.DELIVERED);
        assertThat(ledger.entries(vendorId, 0, 50)).hasSize(1);
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(46_000);
    }

    @Test
    void anEntryCanNeverBeChangedOrRemoved() throws Exception {
        deliver(paidOrder(1));
        UUID entry = ledger.entries(vendorId, 0, 1).get(0).id();
        assertThatThrownBy(() -> jdbc.sql("update ledger_entries set amount = 1 where id = :i").param("i", entry).update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from ledger_entries where id = :i").param("i", entry).update()).hasMessageContaining("append-only");
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(46_000);
    }

    @Test
    void everyTypeKeepsItsDirectionAndOrderEntriesNeedAnOrder() {
        assertThatThrownBy(() -> insert("PAYOUT", 1000, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("COLLECTION", -1000, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("ADJUSTMENT", 0, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("ONLINE_EARNING", 1000, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("COD_COMMISSION", -1000, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("BONUS", 1000, null)).isInstanceOf(DataIntegrityViolationException.class);
        insert("ADJUSTMENT", -500, null);
        insert("ADJUSTMENT", 800, null);
        insert("PAYOUT", -1000, null);
        insert("COLLECTION", 2000, null);
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(1300);
    }

    @Test
    void anOrderThatCannotBePostedStopsItsStatusChange() {
        OrderStatusChanged ghost = new OrderStatusChanged(UUID.randomUUID(), 1, UUID.randomUUID(), vendorId, OrderStatus.OUT_FOR_DELIVERY,
                OrderStatus.DELIVERED, ActorType.SYSTEM);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> publisher.publishEvent(ghost)))
                .hasMessageContaining("not found for the ledger");
    }

    // --- scenario helpers

    private String placeCod(int quantity) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-ledger-cod-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String paidOrder(int quantity) throws Exception {
        String id = placeOnline(quantity);
        ipn(providerOrderId(id), 40000L * quantity + 10000, 0).andExpect(status().isNoContent());
        return id;
    }

    /** The shop confirms and hands the food over, the customer says it arrived. */
    private void deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(customer, post("/api/orders/" + id + "/received"), null).andExpect(status().isOk());
    }

    /** What the order module publishes when an order ends; used for endings no endpoint can reach yet. */
    private void endAs(String orderId, OrderStatus to) {
        UUID id = UUID.fromString(orderId);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> publisher.publishEvent(
                new OrderStatusChanged(id, 1, customerId, vendorId, OrderStatus.OUT_FOR_DELIVERY, to, ActorType.SYSTEM)));
    }

    private void insert(String type, int amount, UUID orderId) {
        jdbc.sql("insert into ledger_entries (vendor_id, type, amount, order_id, acted_by_type) values (:v, :t, :a, :o, 'ADMIN')")
                .param("v", vendorId).param("t", type).param("a", amount).param("o", orderId).update();
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
