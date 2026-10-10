package com.bonbon.backend.shopperformance;

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
import com.bonbon.backend.shopperformance.service.PerformanceService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Which orders fail a shop, the weekly rate, penalty points and the restriction they lead to (backend#103). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PerformanceTests {

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
    PerformanceService performance;

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
    void eachWayAShopFailsAnOrderIsWrittenDownAndCustomerCausedEndingsAreNot() throws Exception {
        String rejected = placeCod(1);
        call(seller, post("/api/merchant/orders/" + rejected + "/reject"), Map.of("reason", "Hết món")).andExpect(status().isOk());
        assertThat(faultTypes(rejected)).containsExactly("SHOP_REJECTED");

        String silent = placeCod(1);
        timers.runOnce(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(orderStatus(silent)).isEqualTo("REJECTED");
        assertThat(faultTypes(silent)).containsExactly("NO_RESPONSE");

        String slow = placeCod(1);
        call(seller, post("/api/merchant/orders/" + slow + "/confirm"), null).andExpect(status().isOk());
        timers.runOnce(Instant.now().plus(Duration.ofMinutes(100)));
        assertThat(orderStatus(slow)).isEqualTo("CANCELLED");
        assertThat(faultTypes(slow)).containsExactly("HANDOVER_TIMEOUT");

        String cancelledByShop = placeCod(1);
        call(seller, post("/api/merchant/orders/" + cancelledByShop + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + cancelledByShop + "/cancel"), Map.of("reason", "Bếp hỏng")).andExpect(status().isOk());
        assertThat(faultTypes(cancelledByShop)).containsExactly("SHOP_CANCELLED");

        String cancelledByCustomer = placeCod(1);
        call(customer, post("/api/orders/" + cancelledByCustomer + "/cancel"), Map.of("reason", "Đổi ý")).andExpect(status().isOk());
        assertThat(faultTypes(cancelledByCustomer)).isEmpty();

        String unpaid = placeOnline(1);
        timers.runOnce(Instant.now().plus(Duration.ofMinutes(20)));
        assertThat(orderStatus(unpaid)).isEqualTo("CANCELLED");
        assertThat(faultTypes(unpaid)).isEmpty();

        String delivered = placeCod(1);
        deliver(delivered);
        assertThat(faultTypes(delivered)).isEmpty();
    }

    @Test
    void aCaseThatRefundsTheWholeOrderIsAFaultButAPartialOneIsNotAndReversalTakesItBack() throws Exception {
        String whole = deliverByShop(placeCod(2));
        String wholeCase = file(whole);
        call(seller, post("/api/merchant/order-cases/" + wholeCase + "/accept"), null).andExpect(status().isOk());
        assertThat(faultTypes(whole)).containsExactly("INCIDENT_FULL_REFUND");

        String partial = deliverByShop(placeCod(2));
        call(customer, post("/api/orders/" + partial + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", itemOf(partial), "quantity", 1))))
                .andExpect(status().isCreated());
        String partialCase = jdbc.sql("select id from order_cases where order_id = :o::uuid").param("o", partial).query(UUID.class).single().toString();
        call(seller, post("/api/merchant/order-cases/" + partialCase + "/accept"), null).andExpect(status().isOk());
        assertThat(faultTypes(partial)).isEmpty();

        String allLines = deliverByShop(placeCod(2));
        call(customer, post("/api/orders/" + allLines + "/incident"), Map.of("type", "MISSING_ITEM", "lines", List.of(Map.of("orderItemId", itemOf(allLines), "quantity", 2))))
                .andExpect(status().isCreated());
        String allCase = jdbc.sql("select id from order_cases where order_id = :o::uuid").param("o", allLines).query(UUID.class).single().toString();
        call(seller, post("/api/merchant/order-cases/" + allCase + "/dispute"), Map.of("note", "Không đúng")).andExpect(status().isOk());
        call(admin, post("/api/admin/order-cases/" + allCase + "/decide"), Map.of("outcome", "UPHELD", "reason", "Thiếu thật")).andExpect(status().isOk());
        assertThat(faultTypes(allLines)).containsExactly("INCIDENT_FULL_REFUND");
        call(admin, post("/api/admin/order-cases/" + allCase + "/reopen"), Map.of("reason", "Quán có bằng chứng mới")).andExpect(status().isOk());
        call(admin, post("/api/admin/order-cases/" + allCase + "/decide"), Map.of("outcome", "DISMISSED", "reason", "Bằng chứng đúng")).andExpect(status().isOk());
        assertThat(faultTypes(allLines)).isEmpty();
    }

    @Test
    void aWeekAboveTheThresholdWithEnoughOrdersCostsOnePointOnlyOnce() throws Exception {
        finishedOrders(8, 2);                              // 10 orders, 2 failed: 20%
        Instant evaluation = nextMonday();
        performance.runWeekly(evaluation);
        performance.runWeekly(evaluation);                 // a second run changes nothing

        var penalty = jdbc.sql("select points, source, finished_orders, fault_orders from shop_penalties where vendor_id = :v").param("v", vendorId).query().singleRow();
        assertThat(penalty).containsEntry("points", 1).containsEntry("source", "WEEKLY").containsEntry("finished_orders", 10).containsEntry("fault_orders", 2);
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_PENALTY")).isEqualTo(1);
        call(seller, get("/api/merchant/performance"), null).andExpect(status().isOk()).andExpect(jsonPath("$.standing.activePoints").value(1))
                .andExpect(jsonPath("$.standing.consequence").value("WARNING")).andExpect(jsonPath("$.penalties[0].points").value(1))
                .andExpect(jsonPath("$.thresholdPercent").value(5)).andExpect(jsonPath("$.minOrders").value(10));
    }

    @Test
    void aShopBelowTheMinimumOrNotAboveTheThresholdIsLeftAlone() throws Exception {
        finishedOrders(7, 2);                              // 9 orders: too few to judge
        performance.runWeekly(nextMonday());
        assertThat(penaltyCount()).isZero();
    }

    @Test
    void exactlyAtTheThresholdIsNotAbove() throws Exception {
        systemSettings.set("shop_performance.min_orders", "2", ActorType.SYSTEM, null);
        systemSettings.set("shop_performance.fault_rate_percent", "50", ActorType.SYSTEM, null);
        try {
            finishedOrders(1, 1);                          // 1 of 2: exactly 50%
            performance.runWeekly(nextMonday());
            assertThat(penaltyCount()).isZero();
            finishedOrders(0, 1);                          // 2 of 3: above 50%
            performance.runWeekly(nextMonday());
            assertThat(penaltyCount()).isEqualTo(1);
        } finally {
            systemSettings.set("shop_performance.min_orders", "10", ActorType.SYSTEM, null);
            systemSettings.set("shop_performance.fault_rate_percent", "5", ActorType.SYSTEM, null);
        }
    }

    @Test
    void threePointsAreAnnouncedFiveDaysBeforeTheRestrictionAndALatePointDropCancelsIt() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        Instant now = Instant.now();
        performance.evaluate(vendorId, now);
        assertThat(restricted()).isFalse();
        assertThat(searchFinds()).isTrue();

        penalty(3, 90);
        performance.evaluate(vendorId, now);
        Instant starts = jdbc.sql("select restriction_starts_at from shop_performance_state where vendor_id = :v").param("v", vendorId).query((rs, n) -> rs.getTimestamp(1).toInstant()).single();
        assertThat(Duration.between(now, starts).toDays()).isBetween(4L, 5L);
        assertThat(Duration.between(now, starts)).isGreaterThanOrEqualTo(Duration.ofDays(5).minusSeconds(5));
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTION_SCHEDULED")).isEqualTo(1);
        call(seller, get("/api/merchant/performance"), null).andExpect(jsonPath("$.standing.consequence").value("RESTRICTION_SCHEDULED")).andExpect(jsonPath("$.standing.reason").value("PERFORMANCE"))
                .andExpect(jsonPath("$.standing.restrictionStartsAt").exists());

        performance.evaluate(vendorId, now.plus(Duration.ofDays(4)));      // not yet
        assertThat(restricted()).isFalse();
        performance.evaluate(vendorId, now.plusSeconds(60));                // repeating does not announce twice
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTION_SCHEDULED")).isEqualTo(1);

        waive(3);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(4)));      // fell below 3 before it began
        assertThat(restricted()).isFalse();
        assertThat(jdbc.sql("select restriction_starts_at from shop_performance_state where vendor_id = :v").param("v", vendorId).query(Timestamp.class).optional().orElse(null)).isNull();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTION_CANCELLED")).isEqualTo(1);
    }

    @Test
    void theRestrictionStartsOnItsDateDropsTheShopFromSearchAndEndsTheMomentPointsFall() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        penalty(3, 90);
        Instant now = Instant.now();
        performance.evaluate(vendorId, now);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(5)).plusSeconds(60));
        assertThat(restricted()).isTrue();
        assertThat(searchFinds()).isFalse();               // out of search
        assertThat(listed()).isTrue();                     // but the plain area list still has it
        assertThat(opensNow()).isTrue();                   // and it still takes orders
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTED")).isEqualTo(1);
        call(seller, get("/api/merchant/performance"), null).andExpect(jsonPath("$.standing.consequence").value("RESTRICTED"));

        waive(3);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(5)).plusSeconds(120));
        assertThat(restricted()).isFalse();
        assertThat(searchFinds()).isTrue();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTION_LIFTED")).isEqualTo(1);
    }

    @Test
    void pointsExpireAfterNinetyDaysAndSixPointsFlagTheShopForReview() throws Exception {
        for (int i = 1; i <= 6; i++) {
            penalty(i, 90);
        }
        performance.evaluate(vendorId, Instant.now());
        assertThat(jdbc.sql("select review_flagged from shop_performance_state where vendor_id = :v").param("v", vendorId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("APPROVED");   // never suspended on its own

        Instant later = Instant.now().plus(Duration.ofDays(91));
        assertThat(jdbc.sql("select coalesce(sum(points), 0) from shop_penalties where vendor_id = :v and status = 'ACTIVE' and expires_at > :now").param("v", vendorId)
                .param("now", Timestamp.from(later)).query(Integer.class).single()).isZero();
        performance.evaluate(vendorId, later);
        assertThat(jdbc.sql("select review_flagged from shop_performance_state where vendor_id = :v").param("v", vendorId).query(Boolean.class).single()).isFalse();
    }

    @Test
    void theShopSeesItsWeeksAndTheOrdersBehindEachFigure() throws Exception {
        String rejected = placeCod(1);
        call(seller, post("/api/merchant/orders/" + rejected + "/reject"), Map.of("reason", "Hết món")).andExpect(status().isOk());
        String monday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();

        call(seller, get("/api/merchant/performance"), null).andExpect(status().isOk()).andExpect(jsonPath("$.weeks.length()").value(9)).andExpect(jsonPath("$.weeks[0].current").value(true))
                .andExpect(jsonPath("$.weeks[0].start").value(monday)).andExpect(jsonPath("$.weeks[0].finishedOrders").value(1)).andExpect(jsonPath("$.weeks[0].faultOrders").value(1))
                .andExpect(jsonPath("$.weeks[0].counted").value(false)).andExpect(jsonPath("$.weeks[1].current").value(false)).andExpect(jsonPath("$.standing.consequence").value("NONE"));
        call(seller, get("/api/merchant/performance/faults?week=" + monday), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("SHOP_REJECTED")).andExpect(jsonPath("$[0].orderId").value(rejected));
        call(seller, get("/api/merchant/performance/faults?week=2026-10-07"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEEK"));
    }

    @Test
    void onlyAShopWithAnApprovedShopCanReadItsPerformance() throws Exception {
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        call(fresh, get("/api/merchant/performance"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        call(customer, get("/api/merchant/performance"), null).andExpect(status().isForbidden());
        call(admin, get("/api/merchant/performance"), null).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/performance")).andExpect(status().isUnauthorized());
    }

    // --- performance helpers

    /** Orders that reached the shop and finished, {@code failed} of them rejected by the shop. */
    private void finishedOrders(int delivered, int failed) throws Exception {
        for (int i = 0; i < delivered; i++) {
            deliver(placeCod(1));
        }
        for (int i = 0; i < failed; i++) {
            call(seller, post("/api/merchant/orders/" + placeCod(1) + "/reject"), Map.of("reason", "Hết món")).andExpect(status().isOk());
        }
    }

    /** The Monday after the week the orders above were made in, just after midnight: the weekly job evaluates that week. */
    private static Instant nextMonday() {
        java.time.LocalDate monday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));
        return monday.atStartOfDay(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().plus(Duration.ofMinutes(20));
    }

    /** An active point of a past week, as the weekly job would have written it. */
    private void penalty(int weeksAgo, int expiresInDays) {
        jdbc.sql("""
                insert into shop_penalties (vendor_id, points, source, week_start, issued_at, expires_at)
                values (:v, 1, 'WEEKLY', current_date - (:w * 7), now(), now() + make_interval(days => :d))""")
                .param("v", vendorId).param("w", weeksAgo).param("d", expiresInDays).update();
    }

    private void waive(int weeksAgo) {
        jdbc.sql("update shop_penalties set status = 'WAIVED' where vendor_id = :v and week_start = current_date - (:w * 7)").param("v", vendorId).param("w", weeksAgo).update();
    }

    private long penaltyCount() {
        return jdbc.sql("select count(*) from shop_penalties where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    private List<String> faultTypes(String order) {
        return jdbc.sql("select type from shop_fault_events where order_id = :o::uuid order by type").param("o", order).query(String.class).list();
    }

    private String orderStatus(String order) {
        return jdbc.sql("select status from orders where id = :o::uuid").param("o", order).query(String.class).single();
    }

    private boolean restricted() {
        return jdbc.sql("select performance_restricted from vendors where id = :v").param("v", vendorId).query(Boolean.class).single();
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
