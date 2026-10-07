package com.bonbon.backend.settlement;

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

/** What the administrator sees and records about money between the platform and a shop (backend#85). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdminSettlementTests {

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
    void theOverviewShowsEachShopsBalanceAndEffectiveRate() throws Exception {
        deliver(paidOrder(1));      // + 46.000, commission 4.000 on 40.000 food
        cashDelivered();            // - 4.000, commission 4.000 on 40.000 food

        String shop = "$.shops.items[?(@.vendorId == '" + vendorId + "')]";
        call(admin, get("/api/admin/settlement/overview?size=100"), null).andExpect(status().isOk())
                .andExpect(jsonPath(shop + ".balance").value(42_000)).andExpect(jsonPath(shop + ".payable").value(42_000))
                .andExpect(jsonPath(shop + ".owed").value(0)).andExpect(jsonPath(shop + ".orders").value(2))
                .andExpect(jsonPath(shop + ".foodValue").value(80_000)).andExpect(jsonPath(shop + ".commission").value(8_000))
                .andExpect(jsonPath(shop + ".effectiveRate").value(10.0)).andExpect(jsonPath(shop + ".name").value("Quán Đối Soát"))
                .andExpect(jsonPath("$.totals.shops").isNumber()).andExpect(jsonPath("$.totals.owedToShops").isNumber())
                .andExpect(jsonPath("$.totals.owedByShops").isNumber()).andExpect(jsonPath("$.totals.effectiveRate").isNumber());

        String body = call(admin, get("/api/admin/settlement/overview?size=1"), null).andReturn().getResponse().getContentAsString();
        long commission = ((Number) JsonPath.read(body, "$.totals.commission")).longValue();
        long net = ((Number) JsonPath.read(body, "$.totals.commissionNet")).longValue();
        long vat = ((Number) JsonPath.read(body, "$.totals.commissionVat")).longValue();
        assertThat(net + vat).isEqualTo(commission);
        assertThat(commission).isGreaterThanOrEqualTo(8_000);
    }

    @Test
    void shopsCanBeFilteredByWhoOwesWhom() throws Exception {
        cashDelivered();            // the shop owes 4.000
        String shop = "$.items[?(@.vendorId == '" + vendorId + "')]";
        call(admin, get("/api/admin/settlement/overview?status=OWED_BY_SHOP&size=100"), null).andExpect(jsonPath("$.shops" + shop.substring(1) + ".owed").value(4_000));
        call(admin, get("/api/admin/settlement/overview?status=OWED_TO_SHOP&size=100"), null).andExpect(jsonPath("$.shops" + shop.substring(1)).isEmpty());
        call(admin, get("/api/admin/settlement/overview?status=BOGUS"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(admin, get("/api/admin/settlement/overview?sort=name"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
        call(admin, get("/api/admin/settlement/overview?sort=balance_asc&size=2"), null).andExpect(status().isOk());
        call(admin, get("/api/admin/settlement/overview?size=500"), null).andExpect(status().isBadRequest());
    }

    @Test
    void aShopFarBelowTheNormalRateIsFlaggedOnlyWithEnoughOrders() throws Exception {
        cashDelivered();
        String row = "$.shops.items[?(@.vendorId == '" + vendorId + "')].lowRate";
        try {
            // Few orders: never flagged, whatever the ratio.
            systemSettings.set("settlement.low_rate_min_orders", "5", ActorType.SYSTEM, null);
            systemSettings.set("settlement.low_rate_ratio", "100", ActorType.SYSTEM, null);
            call(admin, get("/api/admin/settlement/overview?size=100"), null).andExpect(jsonPath(row).value(false));
            // Enough orders and a ratio above the shop's own rate: flagged.
            systemSettings.set("settlement.low_rate_min_orders", "1", ActorType.SYSTEM, null);
            call(admin, get("/api/admin/settlement/overview?size=100"), null).andExpect(jsonPath(row).value(true));
            systemSettings.set("settlement.low_rate_ratio", "0.0001", ActorType.SYSTEM, null);
            call(admin, get("/api/admin/settlement/overview?size=100"), null).andExpect(jsonPath(row).value(false));
        } finally {
            systemSettings.set("settlement.low_rate_min_orders", "5", ActorType.SYSTEM, null);
            systemSettings.set("settlement.low_rate_ratio", "0.5", ActorType.SYSTEM, null);
        }
    }

    @Test
    void aShopsLedgerListsItsEntriesWithOrderNumbers() throws Exception {
        String online = paidOrder(1);
        deliver(online);
        cashDelivered();
        call(admin, get("/api/admin/settlement/vendors/" + vendorId), null).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(42_000))
                .andExpect(jsonPath("$.heldForCases").value(0)).andExpect(jsonPath("$.name").value("Quán Đối Soát"));
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/ledger"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.balance").value(42_000))
                .andExpect(jsonPath("$.items[0].orderNumber").isNumber()).andExpect(jsonPath("$.items[0].amount").value(-4_000))
                .andExpect(jsonPath("$.items[1].type").value("ONLINE_EARNING"));
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/ledger?type=ONLINE_EARNING"), null).andExpect(jsonPath("$.total").value(1));
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/ledger?type=BOGUS"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TYPE"));
        call(admin, get("/api/admin/settlement/vendors/" + UUID.randomUUID() + "/ledger"), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
        call(admin, get("/api/admin/settlement/vendors/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
    }

    @Test
    void theStatementAddsUpAndEndsAtTheBalance() throws Exception {
        deliver(paidOrder(2));      // 80.000 food, 90.000 paid, 8.000 commission, + 82.000
        cashDelivered();            // 40.000 food, 4.000 commission, - 4.000
        record("PAYOUT", 30_000, "FT-STATEMENT-1", null, key()).andExpect(status().isCreated());
        record("ADJUSTMENT", -500, null, "Phạt nhỏ", key()).andExpect(status().isCreated());

        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?granularity=month"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("month")).andExpect(jsonPath("$.openingBalance").value(0))
                .andExpect(jsonPath("$.closingBalance").value(47_500))
                .andExpect(jsonPath("$.periods.length()").value(1))
                .andExpect(jsonPath("$.periods[0].orders").value(2)).andExpect(jsonPath("$.periods[0].onlineOrders").value(1))
                .andExpect(jsonPath("$.periods[0].cashOrders").value(1)).andExpect(jsonPath("$.periods[0].foodValue").value(120_000))
                .andExpect(jsonPath("$.periods[0].deliveryFees").value(20_000)).andExpect(jsonPath("$.periods[0].commission").value(12_000))
                .andExpect(jsonPath("$.periods[0].payouts").value(30_000)).andExpect(jsonPath("$.periods[0].adjustments").value(-500))
                .andExpect(jsonPath("$.periods[0].change").value(47_500)).andExpect(jsonPath("$.periods[0].closingBalance").value(47_500));
        String body = call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?granularity=week"), null).andReturn().getResponse().getContentAsString();
        long net = ((Number) JsonPath.read(body, "$.periods[0].commissionNet")).longValue();
        long vat = ((Number) JsonPath.read(body, "$.periods[0].commissionVat")).longValue();
        assertThat(net + vat).isEqualTo(12_000);
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?granularity=day"), null).andExpect(jsonPath("$.periods.length()").value(1));
        // A range before anything happened is empty, with the balance carried.
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?from=2020-01-01&to=2020-01-31"), null)
                .andExpect(jsonPath("$.periods.length()").value(0)).andExpect(jsonPath("$.closingBalance").value(0));
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?granularity=year"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANULARITY"));
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/statement?from=2026-12-01&to=2026-01-01"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void aPayoutCannotExceedWhatIsPayableAndTheShopIsTold() throws Exception {
        deliver(paidOrder(1));      // balance 46.000
        record("PAYOUT", 46_001, "FT-OVER", null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYOUT_EXCEEDS_PAYABLE"))
                .andExpect(jsonPath("$.balance").value(46_000)).andExpect(jsonPath("$.payable").value(46_000)).andExpect(jsonPath("$.heldForCases").value(0));
        record("PAYOUT", 0, "FT-ZERO", null, key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_INVALID"));
        record("PAYOUT", -5, "FT-NEG", null, key()).andExpect(status().isBadRequest());
        record("PAYOUT", 10_000, " ", null, key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFERENCE_REQUIRED"));

        record("PAYOUT", 20_000, "FT-REAL-1", "Đợt 1", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("PAYOUT"))
                .andExpect(jsonPath("$.amount").value(-20_000)).andExpect(jsonPath("$.reference").value("FT-REAL-1")).andExpect(jsonPath("$.actedByType").value("ADMIN"));
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(26_000);
        record("PAYOUT", 26_001, "FT-OVER-2", null, key()).andExpect(status().isConflict());
        record("PAYOUT", 26_000, "FT-REAL-2", null, key()).andExpect(status().isCreated());
        assertThat(ledger.balanceOf(vendorId)).isZero();
        record("PAYOUT", 1, "FT-NOTHING", null, key()).andExpect(status().isConflict());

        // The shop owner heard about the payouts.
        assertThat(jdbc.sql("select count(*) from notifications where recipient_id = :r and type = 'PAYOUT_RECORDED'")
                .param("r", jdbc.sql("select owner_user_id from vendors where id = :v").param("v", vendorId).query(UUID.class).single()).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aCollectionRepaysADebtButNeverMoreThanIsOwed() throws Exception {
        cashDelivered();            // the shop owes 4.000
        record("COLLECTION", 4_001, "FT-C-OVER", null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COLLECTION_EXCEEDS_DEBT"))
                .andExpect(jsonPath("$.owed").value(4_000));
        record("COLLECTION", 1_500, "FT-C-1", null, key()).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(1_500));
        record("COLLECTION", 2_500, "FT-C-2", null, key()).andExpect(status().isCreated());
        assertThat(ledger.balanceOf(vendorId)).isZero();
        record("COLLECTION", 1, "FT-C-3", null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTHING_OWED"));
        // A shop that owes nothing cannot be paid out either: a debt is not a payable balance.
        cashDelivered();
        record("PAYOUT", 1_000, "FT-P-DEBT", null, key()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYOUT_EXCEEDS_PAYABLE")).andExpect(jsonPath("$.payable").value(0));
    }

    @Test
    void anAdjustmentNeedsAReasonAndMovesTheBalanceBothWays() throws Exception {
        record("ADJUSTMENT", 5_000, null, " ", key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        record("ADJUSTMENT", 0, null, "Không đổi", key()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AMOUNT_INVALID"));
        record("ADJUSTMENT", 5_000, null, "Bù tiền thưởng", key()).andExpect(status().isCreated()).andExpect(jsonPath("$.note").value("Bù tiền thưởng"));
        record("ADJUSTMENT", -5_000, null, "Sửa bút toán trên", key()).andExpect(status().isCreated());
        assertThat(ledger.balanceOf(vendorId)).isZero();
        record("MAGIC", 5_000, null, "x", key()).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/settlement/vendors/" + UUID.randomUUID() + "/entries"), Map.of("type", "ADJUSTMENT", "amount", 1, "note", "x"), key())
                .andExpect(status().isNotFound());
    }

    @Test
    void pressingTheButtonTwiceRecordsTheMoneyOnce() throws Exception {
        deliver(paidOrder(1));
        String same = key();
        record("PAYOUT", 10_000, "FT-DOUBLE", null, same).andExpect(status().isCreated());
        record("PAYOUT", 10_000, "FT-DOUBLE", null, same).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(-10_000));
        assertThat(ledger.balanceOf(vendorId)).isEqualTo(36_000);
        assertThat(ledger.entries(vendorId, 0, 50).stream().filter(e -> e.type().equals("PAYOUT"))).hasSize(1);
        // The same key for another amount is a mistake, not a repeat.
        record("PAYOUT", 11_000, "FT-DOUBLE", null, same).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "PAYOUT", "amount", 1000, "reference", "FT-NOKEY"), null)
                .andExpect(status().isBadRequest());
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "PAYOUT", "amount", 1000, "reference", "FT-SHORT"), "short")
                .andExpect(status().isBadRequest());
    }

    @Test
    void twoPayoutsAtTheSameTimeCannotBothTakeTheLastMoney() throws Exception {
        deliver(paidOrder(1));      // 46.000
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = List.of(
                    () -> record("PAYOUT", 46_000, "FT-RACE-A", null, key()).andReturn().getResponse().getStatus(),
                    () -> record("PAYOUT", 46_000, "FT-RACE-B", null, key()).andReturn().getResponse().getStatus());
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                results.add(f.get());
            }
            assertThat(results).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.balanceOf(vendorId)).isZero();
    }

    @Test
    void onlyAnAdministratorCanSeeOrRecordMoney() throws Exception {
        for (String token : List.of(seller, customer)) {
            call(token, get("/api/admin/settlement/overview"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/settlement/vendors/" + vendorId + "/ledger"), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "ADJUSTMENT", "amount", 1000, "note", "x"), key())
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/settlement/overview")).andExpect(status().isUnauthorized());
        assertThat(ledger.entries(vendorId, 0, 10)).isEmpty();
    }

    // --- scenario helpers

    private ResultActions record(String type, long amount, String reference, String note, String idempotencyKey) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("type", type);
        body.put("amount", amount);
        if (reference != null) {
            body.put("reference", reference);
        }
        if (note != null) {
            body.put("note", note);
        }
        return call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), body, idempotencyKey);
    }

    private String key() {
        return "settle-" + UUID.randomUUID();
    }

    /** A cash order delivered: the shop owes 4.000 commission on 40.000 of food. */
    private void cashDelivered() throws Exception {
        deliver(placeCod(1));
    }

    private String placeCod(int quantity) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-settle-cod-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
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
