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

/** The administrators' queue: reading the evidence, deciding, partial uphold and reopening (backend#101). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class OrderCaseAdminTests {

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
    void theQueueHoldsOnlyWhatTheShopDisputedOrIgnoredAndTheDetailShowsTheEvidence() throws Exception {
        String disputed = file(deliverByShop(placeCod(1)));
        String ignored = file(deliverByShop(placeCod(1)));
        String waiting = file(deliverByShop(placeCod(1)));
        call(seller, post("/api/merchant/order-cases/" + disputed + "/dispute"), Map.of("note", "Có người ký nhận")).andExpect(status().isOk());
        Instant due = jdbc.sql("select shop_response_due_at from order_cases where id = :c::uuid").param("c", ignored).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
        shopCases.escalateOverdue(due.plusSeconds(1));
        jdbc.sql("update order_cases set status = 'AWAITING_SHOP' where id = :c::uuid").param("c", waiting).update();

        String queue = call(admin, get("/api/admin/order-cases?size=100"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(queue, "$.items[*].id")).contains(disputed, ignored).doesNotContain(waiting);
        assertThat(JsonPath.<List<String>>read(queue, "$.items[?(@.id == '" + disputed + "')].vendorName")).containsExactly("Quán Đối Soát");
        assertThat(JsonPath.<List<String>>read(queue, "$.items[?(@.id == '" + disputed + "')].shopResponse")).containsExactly("DISPUTED");
        assertThat(JsonPath.<List<String>>read(queue, "$.items[?(@.id == '" + ignored + "')].shopResponse")).isEmpty();

        call(admin, get("/api/admin/order-cases/" + disputed), null).andExpect(status().isOk()).andExpect(jsonPath("$.orderCase.shopResponseNote").value("Có người ký nhận"))
                .andExpect(jsonPath("$.orderCase.customerName").value("Trần Văn An")).andExpect(jsonPath("$.noResponse").value(false)).andExpect(jsonPath("$.reopened").value(false))
                .andExpect(jsonPath("$.log[0].action").value("FILED")).andExpect(jsonPath("$.log[0].by").value("CUSTOMER")).andExpect(jsonPath("$.log[1].action").value("SHOP_DISPUTED"))
                .andExpect(jsonPath("$.log[1].by").value("SHOP")).andExpect(jsonPath("$.customerHistory.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.vendorName").value("Quán Đối Soát"));
        call(admin, get("/api/admin/order-cases/" + ignored), null).andExpect(jsonPath("$.noResponse").value(true)).andExpect(jsonPath("$.log[1].action").value("NO_RESPONSE"))
                .andExpect(jsonPath("$.log[1].by").value("SYSTEM"));
        call(admin, get("/api/admin/order-cases?status=AWAITING_SHOP&size=100"), null).andExpect(status().isOk());
        call(admin, get("/api/admin/order-cases?status=NOPE"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(admin, get("/api/admin/order-cases?type=NOPE"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TYPE"));
        call(admin, get("/api/admin/order-cases/" + UUID.randomUUID()), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    }

    @Test
    void upholdingRefundsTheCustomerChargesTheShopAndTellsBothSides() throws Exception {
        String order = deliverByShop(paidOrder(2));
        int commission = jdbc.sql("select commission_amount from orders where id = :o::uuid").param("o", order).query(Integer.class).single();
        String id = disputed(order);
        long before = balance();

        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Ảnh cho thấy thiếu hàng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPHELD")).andExpect(jsonPath("$.decidedBy").value("ADMIN")).andExpect(jsonPath("$.reason").value("Ảnh cho thấy thiếu hàng"));
        assertThat(balance()).isEqualTo(before - 90_000 + commission);
        assertThat(held()).isZero();
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("select mode from payment_refunds where case_id = :c::uuid").param("c", id).query(String.class).single()).isEqualTo("GATEWAY");
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_UPHELD")).isEqualTo(1);
        assertThat(notificationsTo(sellerUserId(), "SHOP", "ORDER_CASE_UPHELD")).isEqualTo(1);
        call(customer, get("/api/orders/" + order + "/case"), null).andExpect(jsonPath("$.reason").value("Ảnh cho thấy thiếu hàng")).andExpect(jsonPath("$.decidedBy").value("ADMIN"));
        call(admin, get("/api/admin/order-cases/" + id), null).andExpect(jsonPath("$.log[2].action").value("UPHELD")).andExpect(jsonPath("$.log[2].by").value("ADMIN"));

        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Lần hai")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_OPEN")).andExpect(jsonPath("$.status").value("UPHELD"));
        assertThat(balance()).isEqualTo(before - 90_000 + commission);
    }

    @Test
    void dismissingMovesNoMoneyReleasesTheHoldAndCountsAgainstTheCustomer() throws Exception {
        String first = disputed(deliverByShop(placeCod(1)));
        String order2 = deliverByShop(placeCod(1));
        String second = disputed(order2);
        long before = balance();

        call(admin, post("/api/admin/order-cases/" + first + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Có người ký nhận")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        assertThat(balance()).isEqualTo(before);
        assertThat(jdbc.sql("select count(*) from payment_refunds where case_id = :c::uuid").param("c", first).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from ledger_entries where case_id = :c::uuid").param("c", first).query(Long.class).single()).isZero();
        assertThat(held()).isPositive();                  // the second case still holds its share
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_DISMISSED")).isEqualTo(1);
        call(admin, get("/api/admin/order-cases/" + second), null).andExpect(jsonPath("$.customerHistory.dismissed").value(1))
                .andExpect(jsonPath("$.customerHistory.dismissedLast90Days").value(1));
        call(admin, post("/api/admin/order-cases/" + second + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Cũng vậy")).andExpect(status().isOk());
        assertThat(held()).isZero();
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order2).query(Boolean.class).single()).isFalse();
    }

    @Test
    void anAdministratorCanUpholdOnlyPartOfWhatWasClaimed() throws Exception {
        String order = deliverByShop(placeCod(4));        // 4 x 40.000
        String item = itemOf(order);
        jdbc.sql("update order_items set commission_amount = 1600 where id = :i::uuid").param("i", item).update();
        String id = JsonPath.read(call(customer, post("/api/orders/" + order + "/incident"), Map.of("type", "QUALITY", "lines", List.of(Map.of("orderItemId", item, "quantity", 4)),
                "photoKeys", List.of(photo(order)))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        call(seller, post("/api/merchant/order-cases/" + id + "/dispute"), Map.of("note", "Chỉ nguội một ít")).andExpect(status().isOk());

        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Chỉ hai phần", "lines", List.of(Map.of("orderItemId", item, "quantity", 5))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QUANTITY_INVALID"));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Chỉ hai phần", "lines", List.of(Map.of("orderItemId", UUID.randomUUID().toString(), "quantity", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINE_NOT_IN_CASE"));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "x", "lines", List.of(Map.of("orderItemId", item, "quantity", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINES_NOT_ALLOWED"));
        assertThat(caseStatus(id)).isEqualTo("OPEN");

        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Chỉ hai phần", "lines", List.of(Map.of("orderItemId", item, "quantity", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refundAmount").value(80_000)).andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].refundAmount").value(80_000));
        assertThat(ledgerAmount(id, "CASE_REFUND")).isEqualTo(-80_000);
        assertThat(ledgerAmount(id, "CASE_COMMISSION_REVERSAL")).isEqualTo(800);
        assertThat(jdbc.sql("select amount from payment_refunds where case_id = :c::uuid").param("c", id).query(Integer.class).single()).isEqualTo(80_000);
        assertThat(held()).isZero();
    }

    @Test
    void aWholeOrderCaseCannotBeUpheldInPartAndEveryDecisionNeedsAReason() throws Exception {
        String id = disputed(deliverByShop(placeCod(1)));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Một phần", "lines", List.of(Map.of("orderItemId", itemOf(
                jdbc.sql("select order_id from order_cases where id = :c::uuid").param("c", id).query(UUID.class).single().toString()), "quantity", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LINES_NOT_ALLOWED"));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "MAYBE", "reason", "x")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/order-cases/" + UUID.randomUUID() + "/decide"), Map.of("outcome", "UPHELD", "reason", "x")).andExpect(status().isNotFound());
        assertThat(caseStatus(id)).isEqualTo("OPEN");
    }

    @Test
    void aCaseStillWaitingForTheShopCannotBeDecidedByAnAdministrator() throws Exception {
        String id = file(deliverByShop(placeCod(1)));
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Sớm")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_NOT_OPEN")).andExpect(jsonPath("$.status").value("AWAITING_SHOP"));
        assertThat(caseStatus(id)).isEqualTo("AWAITING_SHOP");
    }

    @Test
    void reopeningAnUpheldCaseThenDismissingItGivesTheShopItsMoneyBackWithAnOppositeEntry() throws Exception {
        String order = deliverByShop(paidOrder(1));
        String id = disputed(order);
        long before = balance();
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Ban đầu")).andExpect(status().isOk());
        long afterUpheld = balance();
        assertThat(afterUpheld).isLessThan(before);

        call(admin, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", "Quán đưa thêm bằng chứng")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(jdbc.sql("select incident_hold from orders where id = :o::uuid").param("o", order).query(Boolean.class).single()).isTrue();
        assertThat(notificationsTo(customerId, "CUSTOMER", "ORDER_CASE_REOPENED")).isEqualTo(1);
        assertThat(notificationsTo(sellerUserId(), "SHOP", "ORDER_CASE_REOPENED")).isEqualTo(1);
        call(admin, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", "Lại")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_NOT_DECIDED"));
        call(admin, get("/api/admin/order-cases/" + id), null).andExpect(jsonPath("$.reopened").value(true)).andExpect(jsonPath("$.log[?(@.action == 'REOPENED')].detail").value("Quán đưa thêm bằng chứng"));

        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Bằng chứng mới đủ thuyết phục")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
        assertThat(balance()).isEqualTo(before);          // the entries stay; one opposite adjustment cancels them
        assertThat(jdbc.sql("select count(*) from ledger_entries where case_id = :c::uuid and type = 'ADJUSTMENT'").param("c", id).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from ledger_entries where case_id = :c::uuid and type = 'CASE_REFUND'").param("c", id).query(Long.class).single()).isEqualTo(1);
        call(admin, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", "Lần nữa")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_ALREADY_REOPENED"));
    }

    @Test
    void reopeningADismissedCaseHoldsTheMoneyAgainUntilTheSecondDecision() throws Exception {
        String order = deliverByShop(placeCod(1));
        String id = disputed(order);
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Không đủ bằng chứng")).andExpect(status().isOk());
        assertThat(held()).isZero();
        call(admin, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", "Khách gửi thêm ảnh")).andExpect(status().isOk());
        assertThat(held()).isPositive();
        long before = balance();
        call(admin, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "Ảnh mới rõ ràng")).andExpect(status().isOk());
        assertThat(held()).isZero();
        assertThat(balance()).isLessThan(before);
        assertThat(ledgerAmount(id, "CASE_REFUND")).isEqualTo(-50_000);
    }

    @Test
    void onlyAdministratorsWithThePermissionsCanReadAndDecide() throws Exception {
        String id = disputed(deliverByShop(placeCod(1)));
        for (String token : List.of(seller, customer)) {
            call(token, get("/api/admin/order-cases"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/order-cases/" + id), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/order-cases/" + id + "/decide"), Map.of("outcome", "UPHELD", "reason", "x")).andExpect(status().isForbidden());
            call(token, post("/api/admin/order-cases/" + id + "/reopen"), Map.of("reason", "x")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/order-cases")).andExpect(status().isUnauthorized());
        assertThat(caseStatus(id)).isEqualTo("OPEN");
    }

    // --- admin helpers

    /** A case the shop disputed, so it is in the administrators' queue. */
    private String disputed(String order) throws Exception {
        String id = file(order);
        call(seller, post("/api/merchant/order-cases/" + id + "/dispute"), Map.of("note", "Không đồng ý")).andExpect(status().isOk());
        return id;
    }

    private String photo(String order) throws Exception {
        return JsonPath.read(mvc.perform(multipart("/api/orders/" + order + "/case-photos").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.photoKey");
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
