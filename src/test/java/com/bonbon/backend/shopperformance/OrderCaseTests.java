package com.bonbon.backend.shopperformance;

import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A customer reporting a delivered order: windows, refund figures, photos and the amount held (backend#99). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderCaseTests {

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
    void notReceivedAsksForTheWholeOrderBackAndHoldsWhatTheShopWouldBear() throws Exception {
        String order = deliverByShop(placeCod(2));        // 80.000 of food + 10.000 delivery
        int commission = jdbc.sql("select commission_amount from orders where id = :o::uuid").param("o", order).query(Integer.class).single();

        String body = call(customer, post("/api/orders/" + order + "/report-not-received"), Map.of("note", "Chờ mãi không thấy")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("NOT_RECEIVED")).andExpect(jsonPath("$.status").value("AWAITING_SHOP"))
                .andExpect(jsonPath("$.refundAmount").value(90_000)).andExpect(jsonPath("$.note").value("Chờ mãi không thấy"))
                .andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.shopResponseDueAt").exists()).andReturn().getResponse().getContentAsString();
        Instant due = Instant.parse(JsonPath.read(body, "$.shopResponseDueAt"));
        assertThat(Duration.between(Instant.now(), due).toMinutes()).isBetween(11L * 60 + 55, 12L * 60 + 1);

        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isTrue();
        call(admin, get("/api/admin/settlement/vendors/" + vendorId), null).andExpect(jsonPath("$.heldForCases").value(90_000 - commission));
        assertThat(notificationsTo(sellerUserId(), "SHOP", "ORDER_CASE_OPENED")).isEqualTo(1);
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_RECEIVED")).isEqualTo(1);

        call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_ALREADY_FILED")).andExpect(jsonPath("$.caseId").value(JsonPath.<String>read(body, "$.id")));
        call(customer, get("/api/orders/" + order + "/case"), null).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(JsonPath.<String>read(body, "$.id")));
    }

    @Test
    void whatIsHeldCannotBePaidOutBeforeTheCaseIsDecided() throws Exception {
        String order = deliverByShop(paidOrder(2));       // online: the platform now owes the shop its share
        long balance = jdbc.sql("select coalesce(sum(amount), 0) from ledger_entries where vendor_id = :v").param("v", vendorId).query(Long.class).single();
        assertThat(balance).isPositive();
        call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isCreated());
        long held = jdbc.sql("select coalesce(sum(amount), 0) from settlement_case_holds where vendor_id = :v and released_at is null").param("v", vendorId)
                .query(Long.class).single();
        assertThat(held).isPositive();
        call(admin, get("/api/admin/settlement/vendors/" + vendorId), null).andExpect(jsonPath("$.payable").value(Math.max(balance - held, 0)));
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "PAYOUT", "amount", balance, "reference", "FT1"), "payout-" + System.nanoTime())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYOUT_EXCEEDS_PAYABLE")).andExpect(jsonPath("$.heldForCases").value(held));
    }

    @Test
    void aCustomerWhoConfirmedReceiptCannotSayNothingArrivedButCanReportAProblem() throws Exception {
        String order = placeCod(1);
        deliver(order);                                   // the customer confirmed
        call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CONFIRMED_RECEIVED"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", itemOf(order), "quantity", 1))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("MISSING_ITEM"));
    }

    @Test
    void theRefundForALineIsWhatWasPaidForItInProportionWithTheDiscountShareTaken() throws Exception {
        String order = deliverByShop(placeCod(3));        // 3 x 40.000
        String item = itemOf(order);
        jdbc.sql("update order_items set allocated_discount = 1000, commission_amount = 12000 where id = :i::uuid").param("i", item).update();
        jdbc.sql("update orders set discount = 1000 where id = :o::uuid").param("o", order).update();

        call(customer, post("/api/orders/" + order + "/incident/quote"), Map.of("lines", List.of(Map.of("orderItemId", item, "quantity", 2)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(79_333)).andExpect(jsonPath("$.lines[0].refundAmount").value(79_333));
        assertThat(caseCount(order)).isZero();             // a quote creates nothing
        call(customer, get("/api/orders/" + order + "/case"), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));

        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", item, "quantity", 2)), "note", "Thiếu hai phần"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(79_333)).andExpect(jsonPath("$.lines[0].name").value("Cơm sườn"));
        assertThat(jdbc.sql("select commission_amount from order_cases where order_id = :o::uuid").param("o", order).query(Integer.class).single()).isEqualTo(8_000);
        assertThat(jdbc.sql("select i.commission_amount from order_case_items i join order_cases c on c.id = i.case_id where c.order_id = :o::uuid").param("o", order)
                .query(Integer.class).single()).isEqualTo(8_000);
    }

    @Test
    void halfAnAmountRoundsUp() throws Exception {
        String order = deliverByShop(placeCod(2));
        String item = itemOf(order);
        jdbc.sql("update order_items set line_total = 5, allocated_discount = 0 where id = :i::uuid").param("i", item).update();
        call(customer, post("/api/orders/" + order + "/incident/quote"), Map.of("lines", List.of(Map.of("orderItemId", item, "quantity", 1)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value(3));
    }

    @Test
    void lineClaimsMustBelongToTheOrderAndStayWithinWhatWasOrdered() throws Exception {
        String order = deliverByShop(placeCod(2));
        String item = itemOf(order);
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", UUID.randomUUID().toString(), "quantity", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINE_NOT_IN_ORDER"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", item, "quantity", 3))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", item, "quantity", 0))))
                .andExpect(status().isBadRequest());
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", item, "quantity", 1),
                Map.of("orderItemId", item, "quantity", 1)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINE_REPEATED"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of())).andExpect(status().isBadRequest());
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "NOT_RECEIVED", "lines", List.of(Map.of("orderItemId", item, "quantity", 1))))
                .andExpect(status().isBadRequest());
        assertThat(caseCount(order)).isZero();
    }

    @Test
    void wrongItemAndQualityNeedAPhotoThatBelongsToTheOrder() throws Exception {
        String order = deliverByShop(placeCod(1));
        String item = itemOf(order);
        List<Object> lines = List.of(Map.of("orderItemId", item, "quantity", 1));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "WRONG_ITEM", "lines", lines)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PHOTO_REQUIRED"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "QUALITY", "lines", lines, "photoKeys", List.of("order-cases/" + UUID.randomUUID() + "/x.png")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTO_INVALID"));
        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "QUALITY", "lines", lines, "photoKeys", List.of("a", "b", "c", "d")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_PHOTOS"));

        mvc.perform(multipart("/api/orders/" + order + "/case-photos").file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isUnsupportedMediaType());
        String key = JsonPath.read(mvc.perform(multipart("/api/orders/" + order + "/case-photos").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isOk()).andExpect(jsonPath("$.url").exists()).andReturn().getResponse().getContentAsString(), "$.photoKey");
        assertThat(key).startsWith("order-cases/" + order + "/");

        call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "QUALITY", "lines", lines, "photoKeys", List.of(key))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(1)).andExpect(jsonPath("$.photos[0].key").value(key)).andExpect(jsonPath("$.photos[0].url").exists());
        // Once filed, more photos are no longer taken for this order.
        mvc.perform(multipart("/api/orders/" + order + "/case-photos").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_FILED"));
    }

    @Test
    void onlyDeliveredOrdersInsideTheWindowCanBeReported() throws Exception {
        String open = placeCod(1);
        call(customer, post("/api/orders/" + open + "/report-not-received"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_DELIVERED"));

        String order = deliverByShop(placeCod(1));
        jdbc.sql("update orders set finished_at = now() - interval '25 hours' where id = :o::uuid").param("o", order).update();
        call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_WINDOW_CLOSED"));
        jdbc.sql("update orders set finished_at = now() - interval '23 hours' where id = :o::uuid").param("o", order).update();
        call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isCreated());
    }

    @Test
    void theWindowIsASettingNotAConstant() throws Exception {
        systemSettings.set("incident.report_window_hours", "2", ActorType.SYSTEM, null);
        try {
            String order = deliverByShop(placeCod(1));
            jdbc.sql("update orders set finished_at = now() - interval '3 hours' where id = :o::uuid").param("o", order).update();
            call(customer, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_WINDOW_CLOSED"));
        } finally {
            systemSettings.set("incident.report_window_hours", "24", ActorType.SYSTEM, null);
        }
    }

    @Test
    void onlyTheCustomerOfTheOrderCanReportIt() throws Exception {
        String order = deliverByShop(placeCod(1));
        String other = tokenOf(newUser("customer2", Role.CUSTOMER), Role.CUSTOMER);
        call(other, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        call(other, get("/api/orders/" + order + "/case"), null).andExpect(status().isNotFound());
        call(seller, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isForbidden());
        call(admin, post("/api/orders/" + order + "/report-not-received"), null).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + order + "/report-not-received")).andExpect(status().isUnauthorized());
        assertThat(caseCount(order)).isZero();
    }

    @Test
    void theCustomersOrderShowsTheLineIdsTheReportNeeds() throws Exception {
        String order = deliverByShop(placeCod(1));
        call(customer, get("/api/orders/" + order), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(itemOf(order)));
    }

    // --- case helpers

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

    private long caseCount(String orderId) {
        return jdbc.sql("select count(*) from order_cases where order_id = :o::uuid").param("o", orderId).query(Long.class).single();
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
