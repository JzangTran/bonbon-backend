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

/** A shop reporting a customer who was not at the door, the customer's answer and the administrator's decision (backend#102). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class NoShowTests {

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
    void theShopCanReportAfterTheMinimumWaitAndTheOrderIsHeldFromTheAutomaticDelivery() throws Exception {
        String order = outForDelivery(placeCod(1));
        call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "Gọi ba lần không bắt máy")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SHOW_TOO_EARLY")).andExpect(jsonPath("$.availableAt").exists());
        waited(order);

        call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", " ")).andExpect(status().isBadRequest());
        String body = call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "Gọi ba lần, chờ 15 phút")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CUSTOMER_NO_SHOW")).andExpect(jsonPath("$.status").value("AWAITING_CUSTOMER")).andExpect(jsonPath("$.refundAmount").value(0))
                .andExpect(jsonPath("$.customerAnswerDueAt").exists()).andReturn().getResponse().getContentAsString();
        Instant due = Instant.parse(JsonPath.read(body, "$.customerAnswerDueAt"));
        assertThat(Duration.between(Instant.now(), due).toMinutes()).isBetween(115L, 121L);
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isTrue();
        assertThat(notificationsTo(customerId, "CUSTOMER", "NO_SHOW_REPORTED")).isEqualTo(1);

        // Three hours later the timer would have delivered it; with the hold it does not.
        timers.runOnce(Instant.now().plus(java.time.Duration.ofHours(4)));
        assertThat(jdbc.sql("select status from orders where id = :o::uuid").param("o", order).query(String.class).single()).isEqualTo("OUT_FOR_DELIVERY");

        call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "Lại")).andExpect(status().isConflict());
        call(customer, get("/api/orders/" + order + "/no-show"), null).andExpect(status().isOk()).andExpect(jsonPath("$.note").value("Gọi ba lần, chờ 15 phút"));
    }

    @Test
    void onlyAnOrderThatIsOutForDeliveryOfThisShopCanBeReported() throws Exception {
        String preparing = placeCod(1);
        call(seller, post("/api/merchant/orders/" + preparing + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + preparing + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + preparing + "/no-show"), Map.of("note", "x")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_OUT_FOR_DELIVERY"));

        String order = waited(outForDelivery(placeCod(1)));
        UUID otherOwner = newUser("seller2", Role.SELLER);
        String other = tokenOf(otherOwner, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(other, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherOwner).update();
        call(other, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "x")).andExpect(status().isNotFound());
        call(customer, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "x")).andExpect(status().isForbidden());
        call(admin, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/api/merchant/orders/" + order + "/no-show")).andExpect(status().isUnauthorized());
        assertThat(caseCount(order)).isZero();
    }

    @Test
    void aPhotoCanBeAttachedAndMustBelongToTheOrder() throws Exception {
        String order = waited(outForDelivery(placeCod(1)));
        call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "Cửa khoá", "photoKey", "order-cases/" + UUID.randomUUID() + "/x.png"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTO_INVALID"));
        String key = JsonPath.read(mvc.perform(multipart("/api/merchant/orders/" + order + "/no-show-photo").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + seller)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.photoKey");
        call(seller, post("/api/merchant/orders/" + order + "/no-show"), Map.of("note", "Cửa khoá", "photoKey", key)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(1)).andExpect(jsonPath("$.photos[0].key").value(key));
        call(customer, get("/api/orders/" + order + "/no-show"), null).andExpect(jsonPath("$.photos.length()").value(1));
    }

    @Test
    void theCustomerSayingTheyReceivedItEndsTheCaseAndDeliversTheOrderWithTheCashCollected() throws Exception {
        String order = reported(placeCod(1));
        call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "RECEIVED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED")).andExpect(jsonPath("$.noShowOutcome").value("CUSTOMER_RECEIVED")).andExpect(jsonPath("$.decidedBy").value("CUSTOMER"));
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("select payment_status from orders where id = :o::uuid").param("o", order).query(String.class).single()).isEqualTo("PAID");
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isFalse();
        assertThat(ledgerCount(order, "COD_COMMISSION")).isEqualTo(1);
        call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "RECEIVED")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_ANSWERED"));
    }

    @Test
    void theCustomerNotBeingAbleToTakeItEndsTheOrderNotDeliveredAndSettlesOnlinePaymentButNotCash() throws Exception {
        String online = reported(paidOrder(2));
        long before = balance();
        call(customer, post("/api/orders/" + online + "/no-show-answer"), Map.of("answer", "UNABLE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPHELD")).andExpect(jsonPath("$.noShowOutcome").value("CUSTOMER_AT_FAULT"));
        assertThat(orderStatus(online)).isEqualTo("NOT_DELIVERED");
        assertThat(ledgerCount(online, "ONLINE_EARNING")).isEqualTo(1);          // the food was made: the shop keeps its earning
        assertThat(balance()).isGreaterThan(before);
        assertThat(jdbc.sql("select count(*) from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid").param("o", online).query(Long.class).single()).isZero();

        String cash = reported(placeCod(1));
        long balanceBeforeCash = balance();
        call(customer, post("/api/orders/" + cash + "/no-show-answer"), Map.of("answer", "UNABLE")).andExpect(status().isOk());
        assertThat(orderStatus(cash)).isEqualTo("NOT_DELIVERED");
        assertThat(ledgerCount(cash, "COD_COMMISSION")).isZero();                // no cash, no sale, no commission
        assertThat(balance()).isEqualTo(balanceBeforeCash);
        assertThat(notificationsTo(sellerUserId(), "SHOP", "NO_SHOW_DECIDED")).isEqualTo(2);
    }

    @Test
    void theCustomerSayingTheShopNeverCameSendsItToTheAdministratorWhoCanCancelAndRefundInFull() throws Exception {
        String order = reported(paidOrder(1));
        call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "SHOP_NEVER_CAME")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "MAYBE")).andExpect(status().isBadRequest());
        String id = JsonPath.read(call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "SHOP_NEVER_CAME", "note", "Tôi chờ ở sảnh, không ai gọi")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.customerAnswer").value("SHOP_NEVER_CAME")).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(orderStatus(order)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(notificationsTo(sellerUserId(), "SHOP", "NO_SHOW_ESCALATED")).isEqualTo(1);

        call(admin, get("/api/admin/order-cases/" + id), null).andExpect(jsonPath("$.orderCase.customerAnswerNote").value("Tôi chờ ở sảnh, không ai gọi")).andExpect(jsonPath("$.log[?(@.action == 'CUSTOMER_SHOP_NEVER_CAME')]").exists());
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Không có cuộc gọi nào")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_SHOW_OUTCOME_REQUIRED"));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "x", "noShowOutcome", "SHOP_NEVER_CAME")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OUTCOME_MISMATCH"));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Không có cuộc gọi nào", "noShowOutcome", "SHOP_NEVER_CAME")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED")).andExpect(jsonPath("$.noShowOutcome").value("SHOP_NEVER_CAME"));

        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        var refund = jdbc.sql("select r.amount, r.reason from payment_refunds r join payments p on p.id = r.payment_id where p.order_id = :o::uuid").param("o", order).query().singleRow();
        assertThat(refund).containsEntry("amount", 50_000).containsEntry("reason", "ORDER_CLOSED");
        assertThat(ledgerCount(order, "ONLINE_EARNING")).isZero();
        assertThat(notificationsTo(customerId, "CUSTOMER", "NO_SHOW_DECIDED")).isEqualTo(1);
    }

    @Test
    void anAdministratorCanAlsoDecideTheCustomerWasAtFaultOrReceivedIt() throws Exception {
        String first = reported(placeCod(1));
        String atFault = silent(first);
        call(admin, post("/api/admin/order-cases/" + atFault + "/decide"), Map.of("outcome", "UPHELD", "reason", "Khách không nghe máy", "noShowOutcome", "CUSTOMER_AT_FAULT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPHELD"));
        assertThat(orderStatus(first)).isEqualTo("NOT_DELIVERED");

        String second = reported(placeCod(1));
        String received = silent(second);
        call(admin, post("/api/admin/order-cases/" + received + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Khách đã nhận", "noShowOutcome", "CUSTOMER_RECEIVED")).andExpect(status().isOk());
        assertThat(orderStatus(second)).isEqualTo("DELIVERED");
        assertThat(jdbc.sql("select payment_status from orders where id = :o::uuid").param("o", second).query(String.class).single()).isEqualTo("PAID");

        String third = reported(placeCod(1));
        String dispute = silent(third);
        call(admin, post("/api/admin/order-cases/" + dispute + "/decide"), Map.of("outcome", "DISMISSED", "reason", "x", "noShowOutcome", "NOPE")).andExpect(status().isBadRequest());
        String incident = file(deliverByShop(placeCod(1)));
        call(seller, post("/api/merchant/order-cases/" + incident + "/dispute"), Map.of("note", "x")).andExpect(status().isOk());
        call(admin, post("/api/admin/order-cases/" + incident + "/decide"), Map.of("outcome", "DISMISSED", "reason", "x", "noShowOutcome", "SHOP_NEVER_CAME")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_SHOW_OUTCOME_NOT_ALLOWED"));
    }

    @Test
    void aCustomerWhoNeverAnswersIsNotTreatedAsHavingAdmittedAnything() throws Exception {
        String order = reported(placeCod(1));
        String id = jdbc.sql("select id from order_cases where order_id = :o::uuid").param("o", order).query(UUID.class).single().toString();
        Instant due = jdbc.sql("select customer_answer_due_at from order_cases where id = :c::uuid").param("c", id).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
        shopCases.escalateOverdue(due.minusSeconds(60));
        assertThat(caseStatus(id)).isEqualTo("AWAITING_CUSTOMER");
        shopCases.escalateOverdue(due.plusSeconds(60));
        assertThat(caseStatus(id)).isEqualTo("OPEN");
        assertThat(jdbc.sql("select customer_answer from order_cases where id = :c::uuid").param("c", id).query(String.class).optional()).isEmpty();
        assertThat(orderStatus(order)).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(notificationsTo(sellerUserId(), "SHOP", "NO_SHOW_ESCALATED")).isEqualTo(1);
        call(admin, get("/api/admin/order-cases/" + id), null).andExpect(jsonPath("$.noResponse").value(true)).andExpect(jsonPath("$.log[?(@.action == 'NO_REPLY')]").exists());
        call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "RECEIVED")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_ANSWERED"));
    }

    @Test
    void theCustomerPressingReceivedWhileTheCaseIsOpenClosesTheCaseToo() throws Exception {
        String order = reported(placeCod(1));
        call(customer, post("/api/orders/" + order + "/received"), null).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("DELIVERED");
        var c = jdbc.sql("select status, no_show_outcome from order_cases where order_id = :o::uuid").param("o", order).query().singleRow();
        assertThat(c).containsEntry("status", "DISMISSED").containsEntry("no_show_outcome", "CUSTOMER_RECEIVED");
        assertThat(jdbc.sql("select payment_status from orders where id = :o::uuid").param("o", order).query(String.class).single()).isEqualTo("PAID");
    }

    @Test
    void onlyTheCustomerOfTheOrderCanAnswer() throws Exception {
        String order = reported(placeCod(1));
        String other = tokenOf(newUser("customer2", Role.CUSTOMER), Role.CUSTOMER);
        call(other, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "RECEIVED")).andExpect(status().isNotFound());
        call(other, get("/api/orders/" + order + "/no-show"), null).andExpect(status().isNotFound());
        call(seller, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "RECEIVED")).andExpect(status().isForbidden());
        assertThat(orderStatus(order)).isEqualTo("OUT_FOR_DELIVERY");
    }

    // --- no-show helpers

    private String outForDelivery(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        return id;
    }

    /** The minimum wait has passed. */
    private String waited(String id) {
        jdbc.sql("update orders set out_for_delivery_at = now() - interval '11 minutes' where id = :o::uuid").param("o", id).update();
        return id;
    }

    /** Out for delivery, waited, and the shop reported the customer. */
    private String reported(String id) throws Exception {
        waited(outForDelivery(id));
        call(seller, post("/api/merchant/orders/" + id + "/no-show"), Map.of("note", "Gọi ba lần không bắt máy")).andExpect(status().isCreated());
        return id;
    }

    /** The customer disputes the report, so it is waiting for an administrator; returns the case id. */
    private String silent(String order) throws Exception {
        return JsonPath.read(call(customer, post("/api/orders/" + order + "/no-show-answer"), Map.of("answer", "SHOP_NEVER_CAME", "note", "Không có ai gọi")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String orderStatus(String order) {
        return jdbc.sql("select status from orders where id = :o::uuid").param("o", order).query(String.class).single();
    }

    private long ledgerCount(String order, String type) {
        return jdbc.sql("select count(*) from ledger_entries where order_id = :o::uuid and type = :t").param("o", order).param("t", type).query(Long.class).single();
    }

    private long caseCount(String orderId) {
        return jdbc.sql("select count(*) from order_cases where order_id = :o::uuid").param("o", orderId).query(Long.class).single();
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
