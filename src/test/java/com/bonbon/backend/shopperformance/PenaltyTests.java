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

/** Waiving, adding and appealing penalty points, and what each does to the restriction (backend#104). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PenaltyTests {

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
    void waivingAPointRecomputesTheStandingAtOnceAndLiftsTheRestriction() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        penalty(3, 90);
        Instant now = Instant.now();
        performance.evaluate(vendorId, now);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(5)).plusSeconds(60));
        assertThat(restricted()).isTrue();
        assertThat(searchFinds()).isFalse();

        String id = penaltyId(3);
        call(admin, post("/api/admin/shop-penalties/" + id + "/waive"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/shop-penalties/" + id + "/waive"), Map.of("reason", "Nền tảng gặp sự cố thông báo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activePoints").value(2)).andExpect(jsonPath("$.consequence").value("WARNING"))
                .andExpect(jsonPath("$.penalties[?(@.id == '" + id + "')].status").value("WAIVED")).andExpect(jsonPath("$.penalties[?(@.id == '" + id + "')].decisionReason").value("Nền tảng gặp sự cố thông báo"));
        assertThat(restricted()).isFalse();
        assertThat(searchFinds()).isTrue();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_PENALTY_WAIVED")).isEqualTo(1);

        call(admin, post("/api/admin/shop-penalties/" + id + "/waive"), Map.of("reason", "Lần hai")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PENALTY_NOT_ACTIVE"));
        call(admin, post("/api/admin/shop-penalties/" + UUID.randomUUID() + "/waive"), Map.of("reason", "x")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PENALTY_NOT_FOUND"));
    }

    @Test
    void anAdministratorCanAddPointsWithAReasonAndThreeOfThemAnnounceARestriction() throws Exception {
        call(admin, post("/api/admin/shops/" + vendorId + "/penalties"), Map.of("points", 0, "reason", "x")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/shops/" + vendorId + "/penalties"), Map.of("points", 4, "reason", "x")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/shops/" + vendorId + "/penalties"), Map.of("points", 1, "reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/shops/" + UUID.randomUUID() + "/penalties"), Map.of("points", 1, "reason", "x")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));

        call(admin, post("/api/admin/shops/" + vendorId + "/penalties"), Map.of("points", 3, "reason", "Bán hàng quá hạn sử dụng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activePoints").value(3)).andExpect(jsonPath("$.consequence").value("RESTRICTION_SCHEDULED")).andExpect(jsonPath("$.penalties[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.penalties[0].reason").value("Bán hàng quá hạn sử dụng"));
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_PENALTY_ADDED")).isEqualTo(1);
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_RESTRICTION_SCHEDULED")).isEqualTo(1);
    }

    @Test
    void theListShowsShopsWithPointsMostFirstAndKeepsShopsWithAnAppealWaiting() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        String listed = call(admin, get("/api/admin/shop-penalties?size=100"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(listed, "$.items[?(@.vendorId == '" + vendorId + "')].activePoints")).containsExactly(2);
        assertThat(JsonPath.<List<String>>read(listed, "$.items[?(@.vendorId == '" + vendorId + "')].name").get(0)).contains(word);
        String strict = call(admin, get("/api/admin/shop-penalties?minPoints=5&size=100"), null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(strict, "$.items[?(@.vendorId == '" + vendorId + "')]")).isEmpty();

        call(seller, post("/api/merchant/penalties/" + penaltyId(1) + "/appeal"), Map.of("reason", "Tuần đó bếp mất điện")).andExpect(status().isNoContent());
        String withAppeal = call(admin, get("/api/admin/shop-penalties?minPoints=5&size=100"), null).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(withAppeal, "$.items[?(@.vendorId == '" + vendorId + "')].pendingAppeals")).containsExactly(1);
        call(admin, get("/api/admin/shop-penalties?minPoints=-1"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_MIN_POINTS"));
    }

    @Test
    void aShopAppealsOnceInsideTheWindowAndWaitingLiftsNothing() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        penalty(3, 90);
        Instant now = Instant.now();
        performance.evaluate(vendorId, now);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(5)).plusSeconds(60));
        assertThat(restricted()).isTrue();
        String id = penaltyId(1);

        call(seller, get("/api/merchant/performance"), null).andExpect(jsonPath("$.penalties[?(@.id == '" + id + "')].canAppeal").value(true));
        call(seller, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(seller, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", "Tuần đó bếp mất điện")).andExpect(status().isNoContent());
        call(seller, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", "Lại")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPEAL_ALREADY_FILED"));
        assertThat(restricted()).isTrue();                   // a pending appeal lifts nothing
        call(seller, get("/api/merchant/performance"), null).andExpect(jsonPath("$.penalties[?(@.id == '" + id + "')].appealStatus").value("PENDING"))
                .andExpect(jsonPath("$.penalties[?(@.id == '" + id + "')].canAppeal").value(false));
        String pending = call(admin, get("/api/admin/shop-penalties/appeals?size=100"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(pending, "$.items[?(@.penaltyId == '" + id + "')].appealReason")).containsExactly("Tuần đó bếp mất điện");
    }

    @Test
    void acceptingAnAppealWaivesThePointAndLiftsTheRestrictionRejectingKeepsIt() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        penalty(3, 90);
        Instant now = Instant.now();
        performance.evaluate(vendorId, now);
        performance.evaluate(vendorId, now.plus(Duration.ofDays(5)).plusSeconds(60));
        String first = penaltyId(1);
        String second = penaltyId(2);
        call(seller, post("/api/merchant/penalties/" + first + "/appeal"), Map.of("reason", "Mất điện")).andExpect(status().isNoContent());
        call(seller, post("/api/merchant/penalties/" + second + "/appeal"), Map.of("reason", "Cũng mất điện")).andExpect(status().isNoContent());

        call(admin, post("/api/admin/shop-penalties/" + first + "/appeal-decision"), Map.of("decision", "REJECT", "reason", "Không có bằng chứng")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activePoints").value(3)).andExpect(jsonPath("$.penalties[?(@.id == '" + first + "')].appealStatus").value("REJECTED"))
                .andExpect(jsonPath("$.penalties[?(@.id == '" + first + "')].status").value("ACTIVE"));
        assertThat(restricted()).isTrue();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_APPEAL_REJECTED")).isEqualTo(1);

        call(admin, post("/api/admin/shop-penalties/" + second + "/appeal-decision"), Map.of("decision", "ACCEPT", "reason", "Có biên bản điện lực")).andExpect(status().isOk())
                .andExpect(jsonPath("$.activePoints").value(2)).andExpect(jsonPath("$.penalties[?(@.id == '" + second + "')].status").value("WAIVED"))
                .andExpect(jsonPath("$.penalties[?(@.id == '" + second + "')].appealStatus").value("ACCEPTED")).andExpect(jsonPath("$.consequence").value("WARNING"));
        assertThat(restricted()).isFalse();
        assertThat(notificationsTo(sellerUserId(), "SHOP", "SHOP_APPEAL_ACCEPTED")).isEqualTo(1);

        call(admin, post("/api/admin/shop-penalties/" + second + "/appeal-decision"), Map.of("decision", "REJECT", "reason", "Lần hai")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPEAL_NOT_PENDING"));
        call(admin, post("/api/admin/shop-penalties/" + penaltyId(3) + "/appeal-decision"), Map.of("decision", "ACCEPT", "reason", "x")).andExpect(status().isConflict());
        call(admin, post("/api/admin/shop-penalties/" + UUID.randomUUID() + "/appeal-decision"), Map.of("decision", "ACCEPT", "reason", "x")).andExpect(status().isNotFound());
        call(admin, post("/api/admin/shop-penalties/" + penaltyId(3) + "/appeal-decision"), Map.of("decision", "MAYBE", "reason", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void anAppealMustComeWithinTheWindowAndForAPointThatStillCounts() throws Exception {
        penalty(1, 90);
        penalty(2, 90);
        jdbc.sql("update shop_penalties set issued_at = now() - interval '8 days' where vendor_id = :v and week_start = current_date - 7").param("v", vendorId).update();
        call(seller, post("/api/merchant/penalties/" + penaltyId(1) + "/appeal"), Map.of("reason", "Muộn")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPEAL_WINDOW_CLOSED")).andExpect(jsonPath("$.deadline").exists());
        call(seller, get("/api/merchant/performance"), null).andExpect(jsonPath("$.penalties[?(@.id == '" + penaltyId(1) + "')].canAppeal").value(false));

        waive(2);
        call(seller, post("/api/merchant/penalties/" + penaltyId(2) + "/appeal"), Map.of("reason", "Đã miễn")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PENALTY_NOT_ACTIVE"));
        call(seller, post("/api/merchant/penalties/" + UUID.randomUUID() + "/appeal"), Map.of("reason", "x")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PENALTY_NOT_FOUND"));
    }

    @Test
    void aShopCannotAppealAnotherShopsPoint() throws Exception {
        penalty(1, 90);
        UUID otherOwner = newUser("seller2", Role.SELLER);
        String other = tokenOf(otherOwner, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(other, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherOwner).update();
        call(other, post("/api/merchant/penalties/" + penaltyId(1) + "/appeal"), Map.of("reason", "Không phải của tôi")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select appeal_status from shop_penalties where id = :i::uuid").param("i", penaltyId(1)).query(String.class).optional()).isEmpty();
    }

    @Test
    void onlyAdministratorsWithThePermissionsManagePointsAndOnlyShopsAppeal() throws Exception {
        penalty(1, 90);
        String id = penaltyId(1);
        for (String token : List.of(seller, customer)) {
            call(token, get("/api/admin/shop-penalties"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/shop-penalties/appeals"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/shops/" + vendorId + "/penalties"), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/shop-penalties/" + id + "/waive"), Map.of("reason", "x")).andExpect(status().isForbidden());
            call(token, post("/api/admin/shops/" + vendorId + "/penalties"), Map.of("points", 1, "reason", "x")).andExpect(status().isForbidden());
            call(token, post("/api/admin/shop-penalties/" + id + "/appeal-decision"), Map.of("decision", "ACCEPT", "reason", "x")).andExpect(status().isForbidden());
        }
        call(customer, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", "x")).andExpect(status().isForbidden());
        call(admin, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", "x")).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/shop-penalties")).andExpect(status().isUnauthorized());
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        call(fresh, post("/api/merchant/penalties/" + id + "/appeal"), Map.of("reason", "x")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        call(admin, get("/api/admin/shops/" + vendorId + "/penalties"), null).andExpect(status().isOk()).andExpect(jsonPath("$.penalties.length()").value(1))
                .andExpect(jsonPath("$.name").value(org.hamcrest.Matchers.containsString(word)));
        call(admin, get("/api/admin/shops/" + UUID.randomUUID() + "/penalties"), null).andExpect(status().isNotFound());
    }

    // --- penalty helpers

    private String penaltyId(int weeksAgo) {
        return jdbc.sql("select id from shop_penalties where vendor_id = :v and week_start = current_date - (:w * 7)").param("v", vendorId).param("w", weeksAgo).query(UUID.class).single().toString();
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
