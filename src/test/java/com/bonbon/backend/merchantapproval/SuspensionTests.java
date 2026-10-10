package com.bonbon.backend.merchantapproval;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
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
import com.bonbon.backend.order.OrderStatusChanged;
import com.bonbon.backend.merchantapproval.service.SuspensionService;
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
import org.springframework.mock.web.MockMultipartFile;
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

/** Suspending a shop with the legal notice, what it does, and calling it off or lifting it (backend#105). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SuspensionTests {

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
    SuspensionService suspensions;

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
    double lat;
    double lng;
    String word;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        lat = -80 + (System.nanoTime() % 1000) * 0.01;
        lng = 151.2;
        word = "q" + Long.toString(System.nanoTime(), 36);

        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Đối Soát " + word, "phone", "0912345678", "email", "q@example.com", "placeId", place));
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
    void aSuspensionNeedsAtLeastFiveDaysNoticeAndTheShopKeepsWorkingUntilThen() throws Exception {
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Bán hàng quá hạn sử dụng", "effectiveAt", Instant.now().plus(Duration.ofDays(3)).toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTICE_TOO_SHORT")).andExpect(jsonPath("$.earliest").exists());
        assertThat(openSuspensions()).isZero();

        String body = call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Bán hàng quá hạn sử dụng")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("SCHEDULED")).andExpect(jsonPath("$.status").value("SCHEDULED")).andReturn().getResponse().getContentAsString();
        Instant effective = Instant.parse(JsonPath.read(body, "$.effectiveAt"));
        assertThat(Duration.between(Instant.now(), effective)).isBetween(Duration.ofDays(5).minusSeconds(30), Duration.ofDays(5).plusSeconds(5));
        assertThat(vendorStatus()).isEqualTo("APPROVED");
        assertThat(opensNow()).isTrue();
        assertThat(searchFinds()).isTrue();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_SUSPENSION_SCHEDULED")).isEqualTo(1);
        call(seller, get("/api/merchant/suspension"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED")).andExpect(jsonPath("$.reason").value("Bán hàng quá hạn sử dụng"))
                .andExpect(jsonPath("$.effectiveAt").exists());

        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Lần hai")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUSPENSION_ALREADY_OPEN"));
        // a later date is fine too
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspension/cancel"), Map.of("reason", "Đổi ngày")).andExpect(status().isOk());
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Bán hàng quá hạn sử dụng", "effectiveAt", Instant.now().plus(Duration.ofDays(9)).toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void whenTheNoticeRunsOutTheShopIsSuspendedButCanFinishItsOrdersAndReadItsMoney() throws Exception {
        String inProgress = placeCod(1);
        call(seller, post("/api/merchant/orders/" + inProgress + "/confirm"), null).andExpect(status().isOk());
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Vi phạm chính sách")).andExpect(status().isCreated());

        assertThat(suspensions.applyDue(Instant.now().plus(Duration.ofDays(4)))).isGreaterThanOrEqualTo(0);
        assertThat(vendorStatus()).isEqualTo("APPROVED");                       // still inside the notice
        suspensions.applyDue(Instant.now().plus(Duration.ofDays(5)).plusSeconds(60));
        assertThat(vendorStatus()).isEqualTo("SUSPENDED");
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_SUSPENDED")).isEqualTo(1);
        suspensions.applyDue(Instant.now().plus(Duration.ofDays(6)));            // a second run changes nothing
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_SUSPENDED")).isEqualTo(1);

        // gone for customers
        assertThat(searchFinds()).isFalse();
        assertThat(listed()).isFalse();
        mvc.perform(get("/api/vendors/" + vendorId + "/menu")).andExpect(status().isNotFound());
        call(customer, post("/api/orders"), Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dishId, "quantity", 1))), "key-susp-" + System.nanoTime())
                .andExpect(status().is4xxClientError());

        // but the seller can still log in, see why, finish what is in progress and read the money
        call(seller, get("/api/merchant/suspension"), null).andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.reason").value("Vi phạm chính sách"));
        call(seller, post("/api/merchant/orders/" + inProgress + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + inProgress + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + inProgress + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
        call(seller, get("/api/merchant/earnings"), null).andExpect(status().isOk());
        call(seller, get("/api/merchant/shop"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"));
        // not the menu or the profile
        call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Mới")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", true)).andExpect(status().isConflict());
    }

    @Test
    void anAdministratorCanCallOffASuspensionDuringTheNotice() throws Exception {
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Đang tranh chấp")).andExpect(status().isCreated());
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspension/cancel"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspension/cancel"), Map.of("reason", "Tranh chấp đã xong")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.endReason").value("Tranh chấp đã xong"));
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_SUSPENSION_CANCELLED")).isEqualTo(1);
        suspensions.applyDue(Instant.now().plus(Duration.ofDays(6)));
        assertThat(vendorStatus()).isEqualTo("APPROVED");
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspension/cancel"), Map.of("reason", "Lại")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_SCHEDULED_SUSPENSION"));
        call(seller, get("/api/merchant/suspension"), null).andExpect(jsonPath("$.status").value("NONE"));
    }

    @Test
    void onlyACompetentAuthoritysRequestSuspendsAtOnceAndItsReferenceIsKept() throws Exception {
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Yêu cầu cơ quan", "immediate", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTHORITY_REFERENCE_REQUIRED"));
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Yêu cầu cơ quan", "immediate", true, "authorityReference", "CV 123/QLTT ngày 08/10/2026",
                "effectiveAt", Instant.now().plus(Duration.ofDays(6)).toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EFFECTIVE_AT_NOT_ALLOWED"));
        assertThat(vendorStatus()).isEqualTo("APPROVED");

        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Yêu cầu cơ quan", "immediate", true, "authorityReference", "CV 123/QLTT ngày 08/10/2026"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("IMMEDIATE")).andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.authorityReference").value("CV 123/QLTT ngày 08/10/2026"));
        assertThat(vendorStatus()).isEqualTo("SUSPENDED");
        assertThat(searchFinds()).isFalse();
    }

    @Test
    void aSuspendedShopCanBeReinstatedAndSuspendedAgainWithTheWholeHistoryKept() throws Exception {
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Cơ quan yêu cầu", "immediate", true, "authorityReference", "CV 1")).andExpect(status().isCreated());
        call(admin, post("/api/admin/merchants/" + vendorId + "/reinstate"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/merchants/" + vendorId + "/reinstate"), Map.of("reason", "Đã khắc phục")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIFTED"));
        assertThat(vendorStatus()).isEqualTo("APPROVED");
        assertThat(searchFinds()).isTrue();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_REINSTATED")).isEqualTo(1);
        call(admin, post("/api/admin/merchants/" + vendorId + "/reinstate"), Map.of("reason", "Lại")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_SUSPENDED"));

        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Vi phạm lần hai")).andExpect(status().isCreated());
        call(admin, get("/api/admin/merchants/" + vendorId + "/suspensions"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("SCHEDULED")).andExpect(jsonPath("$.items[1].status").value("LIFTED"));
    }

    @Test
    void aShopThatClosedDuringTheNoticeIsLeftAloneWhenTheDateComes() throws Exception {
        call(admin, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "Vi phạm")).andExpect(status().isCreated());
        jdbc.sql("update vendors set status = 'CLOSED' where id = :v").param("v", vendorId).update();
        suspensions.applyDue(Instant.now().plus(Duration.ofDays(6)));
        assertThat(vendorStatus()).isEqualTo("CLOSED");
        assertThat(jdbc.sql("select status from merchant_suspensions where vendor_id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void onlyAnApprovedShopCanBeSuspendedAndOnlyAdministratorsDoIt() throws Exception {
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        call(fresh, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Chưa Duyệt", "phone", "0912345678", "email", "c@example.com", "placeId", geocoder.autocomplete("Toà S2", null, null).get(0).placeId()));
        UUID draft = jdbc.sql("select id from vendors where name = 'Quán Chưa Duyệt' order by created_at desc limit 1").query(UUID.class).single();
        call(admin, post("/api/admin/merchants/" + draft + "/suspend"), Map.of("reason", "x")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED")).andExpect(jsonPath("$.status").value("DRAFT"));
        call(admin, post("/api/admin/merchants/" + UUID.randomUUID() + "/suspend"), Map.of("reason", "x")).andExpect(status().isNotFound());

        for (String token : List.of(seller, customer)) {
            call(token, post("/api/admin/merchants/" + vendorId + "/suspend"), Map.of("reason", "x")).andExpect(status().isForbidden());
            call(token, post("/api/admin/merchants/" + vendorId + "/suspension/cancel"), Map.of("reason", "x")).andExpect(status().isForbidden());
            call(token, post("/api/admin/merchants/" + vendorId + "/reinstate"), Map.of("reason", "x")).andExpect(status().isForbidden());
            call(token, get("/api/admin/merchants/" + vendorId + "/suspensions"), null).andExpect(status().isForbidden());
        }
        call(customer, get("/api/merchant/suspension"), null).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/merchants/" + vendorId + "/suspend")).andExpect(status().isUnauthorized());
        assertThat(vendorStatus()).isEqualTo("APPROVED");
    }

    // --- suspension helpers

    private String vendorStatus() {
        return jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single();
    }

    private long openSuspensions() {
        return jdbc.sql("select count(*) from merchant_suspensions where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    private boolean opensNow() throws Exception {
        String body = call(seller, get("/api/merchant/shop"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Boolean>read(body, "$.openNow");
    }

    private boolean listed() throws Exception {
        return areaList(null).contains(word);
    }

    private boolean searchFinds() throws Exception {
        return areaList(word).contains(word);
    }

    private String areaList(String q) throws Exception {
        var request = get("/api/vendors").param("lat", String.valueOf(lat + 0.003)).param("lng", String.valueOf(lng)).param("size", "50");
        if (q != null) {
            request.param("q", q);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
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
