package com.bonbon.backend.settlement;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.bonbon.backend.common.geo.Geocoder;
import com.bonbon.backend.settlement.service.CommissionDebtService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

/**
 * Commission a shop owes: weekly statements, reminders, escalation with its notices, the credit limit and extensions
 * (backend#87). A clock the test moves stands in for the calendar; entries are written through the administrator's
 * endpoint, so no order flow is needed to create a debt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, CommissionDebtTests.Clocks.class })
class CommissionDebtTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Starts at the moment it is built and only moves when a test says so. */
    static class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

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
    MovableClock clock;

    @Autowired
    CommissionDebtService debt;

    String admin;
    String seller;
    String customer;
    UUID vendorId;
    double lat;
    double lng;
    int keySeq;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        lat = -70 + (System.nanoTime() % 1000) * 0.01;
        lng = 151.2;
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Nợ Phở", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Phở bò đặc biệt", "price", 40000, "stockQuantity", 50));
        customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        // Wednesday 5 March 2031, mid-morning: every test starts a week away from the shared database's real data.
        clock.set(at("2031-03-05T10:00"));
    }

    @Test
    void theWeekThatClosedIsBilledOnceAndTheShopIsToldWithTheDueDate() throws Exception {
        adjust(-120_000);
        clock.set(at("2031-03-11T00:10"));          // the Monday has passed
        debt.runDaily(clock.instant());
        debt.runDaily(clock.instant());              // a repeated run bills nothing twice

        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/commission-statements"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].kind").value("WEEKLY"))
                .andExpect(jsonPath("$.items[0].amountDue").value(120_000)).andExpect(jsonPath("$.items[0].unpaid").value(120_000))
                .andExpect(jsonPath("$.items[0].status").value("OPEN")).andExpect(jsonPath("$.items[0].periodStart").value("2031-03-03"))
                .andExpect(jsonPath("$.items[0].dueAt").value(at("2031-03-17T00:00").toString()))
                .andExpect(jsonPath("$.standing.stage").value("NONE")).andExpect(jsonPath("$.standing.owed").value(120_000))
                .andExpect(jsonPath("$.standing.overdue").value(0));
        assertThat(notifications("COMMISSION_STATEMENT")).isEqualTo(1);
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountDue").value(120_000));
    }

    @Test
    void aSmallDebtCarriesIntoTheNextWeekInsteadOfBeingBilled() throws Exception {
        adjust(-40_000);                              // below the 50.000 minimum
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        assertThat(statementCount()).isZero();

        clock.set(at("2031-03-12T10:00"));
        adjust(-30_000);                              // 70.000 owed at the next close
        clock.set(at("2031-03-18T00:10"));
        debt.runDaily(clock.instant());
        call(admin, get("/api/admin/settlement/vendors/" + vendorId + "/commission-statements"), null).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountDue").value(70_000)).andExpect(jsonPath("$.items[0].periodStart").value("2031-03-10"));
    }

    @Test
    void aShopThatOwesNothingOrIsOwedMoneyGetsNoStatement() throws Exception {
        adjust(80_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        assertThat(statementCount()).isZero();
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.standing.stage").value("NONE")).andExpect(jsonPath("$.standing.owed").value(0));
    }

    @Test
    void creditsAfterTheClosePayTheStatementAndOnlyTheLateRemainderIsOverdue() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        clock.set(at("2031-03-12T09:00"));
        collect(40_000);
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.items[0].unpaid").value(60_000))
                .andExpect(jsonPath("$.items[0].status").value("OPEN")).andExpect(jsonPath("$.standing.overdue").value(0));

        clock.set(at("2031-03-17T00:10"));            // the due date has passed
        debt.runDaily(clock.instant());
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.items[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.standing.overdue").value(60_000)).andExpect(jsonPath("$.standing.stage").value("NONE"));

        collect(60_000);
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.items[0].status").value("PAID"))
                .andExpect(jsonPath("$.items[0].unpaid").value(0)).andExpect(jsonPath("$.standing.overdue").value(0))
                .andExpect(jsonPath("$.standing.owed").value(0));
    }

    @Test
    void aReminderGoesOutTwoDaysBeforeTheDueDateAndOnlyOnce() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        clock.set(at("2031-03-14T00:10"));            // five days before
        debt.runDaily(clock.instant());
        assertThat(notifications("COMMISSION_REMINDER")).isZero();
        clock.set(at("2031-03-15T00:10"));            // two days before
        debt.runDaily(clock.instant());
        debt.runDaily(clock.instant());
        assertThat(notifications("COMMISSION_REMINDER")).isEqualTo(1);
    }

    @Test
    void anOverdueShopIsWarnedThenRestrictedThenPausedThenFlaggedEachAfterItsNotice() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());               // statement due 17 March

        clock.set(at("2031-03-17T12:00"));            // overdue for half a day: nothing yet
        debt.runDaily(clock.instant());
        stage("NONE");

        clock.set(at("2031-03-18T00:10"));            // one day overdue: the notice of what comes next
        debt.runDaily(clock.instant());
        stage("OVERDUE");
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.standing.overdueSince").value(at("2031-03-17T00:00").toString()))
                .andExpect(jsonPath("$.standing.restrictAt").value(at("2031-03-24T00:00").toString()))
                .andExpect(jsonPath("$.standing.pauseAt").value(at("2031-03-31T00:00").toString()));
        assertThat(notifications("COMMISSION_OVERDUE")).isEqualTo(1);
        assertThat(searchFinds("Phở")).isTrue();

        clock.set(at("2031-03-23T00:10"));            // 6 days overdue: still visible
        debt.runDaily(clock.instant());
        stage("OVERDUE");

        clock.set(at("2031-03-24T00:10"));            // 7 days overdue and the notice is 6 days old
        debt.runDaily(clock.instant());
        stage("RESTRICTED");
        assertThat(searchFinds("Phở")).isFalse();     // gone from search
        assertThat(listed()).isTrue();                // but the plain area list still has it
        assertThat(opensNow()).isTrue();              // and it still takes orders
        assertThat(notifications("COMMISSION_RESTRICTED")).isEqualTo(1);

        clock.set(at("2031-03-30T00:10"));
        debt.runDaily(clock.instant());
        stage("RESTRICTED");
        clock.set(at("2031-03-31T00:10"));            // 14 days overdue, restriction notice 7 days old
        debt.runDaily(clock.instant());
        stage("PAUSED");
        assertThat(opensNow()).isFalse();
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", true)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAUSED_FOR_COMMISSION"));
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false)).andExpect(status().isOk());
        assertThat(notifications("COMMISSION_PAUSED")).isEqualTo(1);

        clock.set(at("2031-04-16T00:10"));            // 30 days overdue
        debt.runDaily(clock.instant());
        stage("REVIEW");
        call(admin, get("/api/admin/settlement/overview?status=OWED_BY_SHOP&size=100"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.shops.items[?(@.vendorId == '" + vendorId + "')].commissionStage").value("REVIEW"));
        assertThat(opensNow()).isFalse();             // never suspended on its own
        assertThat(jdbc.sql("select status from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo("APPROVED");
    }

    @Test
    void payingWhatIsOverdueLiftsEveryRestrictionAtOnce() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        for (String when : List.of("2031-03-18T00:10", "2031-03-24T00:10", "2031-03-31T00:10")) {
            clock.set(at(when));
            debt.runDaily(clock.instant());
        }
        stage("PAUSED");
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", false)).andExpect(status().isOk());

        clock.set(at("2031-04-01T10:00"));
        collect(100_000);                              // no job needed: the credit itself lifts everything
        stage("NONE");
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.standing.overdueSince").doesNotExist());
        assertThat(searchFinds("Phở")).isTrue();
        call(seller, put("/api/merchant/shop/accepting-orders"), Map.of("accepting", true)).andExpect(status().isOk());
        assertThat(opensNow()).isTrue();
        assertThat(notifications("COMMISSION_CLEARED")).isEqualTo(1);
    }

    @Test
    void aStepWaitsForItsNoticeWhenTheJobDidNotRunOnTime() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        // The job was down for a week and a half: 10 days overdue, but no notice has gone out yet.
        clock.set(at("2031-03-27T00:10"));
        debt.runDaily(clock.instant());
        stage("OVERDUE");                              // the notice goes out now, not the restriction
        clock.set(at("2031-03-31T23:00"));            // 4 days after the notice
        debt.runDaily(clock.instant());
        stage("OVERDUE");
        clock.set(at("2031-04-01T00:20"));            // 5 days after the notice
        debt.runDaily(clock.instant());
        stage("RESTRICTED");
    }

    @Test
    void aDebtOverTheLimitIsBilledAtOnceWithAShortDeadline() throws Exception {
        adjust(-1_500_000);
        assertThat(statementCount()).isZero();
        adjust(-600_000);                              // 2.100.000 passes the 2.000.000 limit
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].kind").value("LIMIT")).andExpect(jsonPath("$.items[0].amountDue").value(2_100_000))
                .andExpect(jsonPath("$.items[0].dueAt").value(at("2031-03-08T10:00").toString()));
        adjust(-100_000);                              // an open statement already runs out in 3 days: no second one
        assertThat(statementCount()).isEqualTo(1);
        assertThat(notifications("COMMISSION_STATEMENT")).isEqualTo(1);
    }

    @Test
    void anAdministratorCanExtendAStatementAndTheClockRestartsFromTheNewDate() throws Exception {
        adjust(-100_000);
        clock.set(at("2031-03-11T00:10"));
        debt.runDaily(clock.instant());
        clock.set(at("2031-03-24T00:10"));
        debt.runDaily(clock.instant());
        debt.runDaily(clock.instant());
        String id = JsonPath.read(call(seller, get("/api/merchant/settlement/statements"), null).andReturn().getResponse().getContentAsString(), "$.items[0].id");
        stage("OVERDUE");

        call(seller, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-03-30T00:00").toString(), "reason", "Đợi"))
                .andExpect(status().isForbidden());
        call(admin, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-03-16T00:00").toString(), "reason", "Đợi"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUE_DATE_INVALID"));
        call(admin, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-05-30T00:00").toString(), "reason", "Đợi"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUE_DATE_TOO_FAR"));
        call(admin, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-03-30T00:00").toString(), "reason", " "))
                .andExpect(status().isBadRequest());
        call(admin, post("/api/admin/settlement/statements/" + UUID.randomUUID() + "/extend"), Map.of("dueAt", at("2031-03-30T00:00").toString(), "reason", "Đợi"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STATEMENT_NOT_FOUND"));

        call(admin, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-03-30T00:00").toString(), "reason", "Chủ quán nằm viện"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.extended").value(true)).andExpect(jsonPath("$.extensionReason").value("Chủ quán nằm viện"))
                .andExpect(jsonPath("$.dueAt").value(at("2031-03-30T00:00").toString())).andExpect(jsonPath("$.status").value("OPEN"));
        stage("NONE");                                 // not overdue any more, so the warning is lifted

        clock.set(at("2031-03-31T00:10"));            // overdue again, counting from the new date
        debt.runDaily(clock.instant());
        call(seller, get("/api/merchant/settlement/statements"), null).andExpect(jsonPath("$.standing.overdueSince").value(at("2031-03-30T00:00").toString()));
        collect(100_000);
        call(admin, post("/api/admin/settlement/statements/" + id + "/extend"), Map.of("dueAt", at("2031-04-10T00:00").toString(), "reason", "Muộn"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATEMENT_PAID"));
    }

    @Test
    void onlyAnApprovedShopCanReadItsStatements() throws Exception {
        String fresh = tokenOf(newUser("seller3", Role.SELLER), Role.SELLER);
        call(fresh, get("/api/merchant/settlement/statements"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
        call(customer, get("/api/merchant/settlement/statements"), null).andExpect(status().isForbidden());
        call(admin, get("/api/merchant/settlement/statements"), null).andExpect(status().isForbidden());
        mvc.perform(get("/api/merchant/settlement/statements")).andExpect(status().isUnauthorized());
        call(seller, get("/api/admin/settlement/vendors/" + vendorId + "/commission-statements"), null).andExpect(status().isForbidden());
    }

    // --- helpers

    private void adjust(long amount) throws Exception {
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "ADJUSTMENT", "amount", amount, "note", "Kiểm thử"),
                "debt-" + System.nanoTime() + keySeq++).andExpect(status().is2xxSuccessful());
    }

    private void collect(long amount) throws Exception {
        call(admin, post("/api/admin/settlement/vendors/" + vendorId + "/entries"), Map.of("type", "COLLECTION", "amount", amount, "reference", "FT" + System.nanoTime()),
                "debt-" + System.nanoTime() + keySeq++).andExpect(status().is2xxSuccessful());
    }

    private void stage(String expected) {
        assertThat(jdbc.sql("select commission_stage from vendors where id = :v").param("v", vendorId).query(String.class).single()).isEqualTo(expected);
    }

    private long statementCount() {
        return jdbc.sql("select count(*) from commission_statements where vendor_id = :v").param("v", vendorId).query(Long.class).single();
    }

    private int notifications(String type) {
        return jdbc.sql("select count(*) from notifications n join vendors v on v.owner_user_id = n.recipient_id where v.id = :v and n.type = :t")
                .param("v", vendorId).param("t", type).query(Integer.class).single();
    }

    private boolean opensNow() throws Exception {
        String body = call(seller, get("/api/merchant/shop"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Boolean>read(body, "$.openNow");
    }

    private boolean listed() throws Exception {
        return areaList(null).contains("Quán Nợ Phở");
    }

    private boolean searchFinds(String word) throws Exception {
        return areaList(word).contains("Quán Nợ Phở");
    }

    private String areaList(String q) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/vendors").param("lat", String.valueOf(lat + 0.003)).param("lng", String.valueOf(lng));
        if (q != null) {
            request.param("q", q);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static Instant at(String vietnamLocal) {
        return LocalDateTime.parse(vietnamLocal).atZone(VIETNAM).toInstant();
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

    private static List<Map<String, Object>> allWeek() {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            windows.add(Map.of("weekday", day, "opensAt", "00:00", "closesAt", "12:00"));
            windows.add(Map.of("weekday", day, "opensAt", "12:00", "closesAt", "00:00"));
        }
        return windows;
    }
}
