package com.bonbon.backend.identity.legal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.identity.legal.service.LegalAdminService;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Publishing a legal document and asking people to accept it again (backend#130). A clock the test moves stands in for the
 * calendar. Each test uses a different kind of document so a version waiting for its date never blocks another test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, LegalPublishingTests.Clocks.class })
class LegalPublishingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @Autowired
    LegalAdminService legal;

    @MockitoBean
    EmailSender emailSender;

    String admin;
    Instant start;

    @BeforeEach
    void setUp() {
        start = Instant.now();
        clock.set(start);
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
    }

    @Test
    void aDraftIsEditedPublishedWithAHashAndCancelledBeforeItTakesEffect() throws Exception {
        String draft = createDraft("CUSTOMER_TERMS", "Điều khoản khách v2", "Thêm mục về hoàn tiền", "Nội dung đầy đủ của bản hai.", true);
        call(admin, post("/api/admin/legal-documents"), Map.of("type", "CUSTOMER_TERMS", "title", "Bản khác", "content", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_EXISTS"));
        call(admin, patch("/api/admin/legal-documents/" + draft), Map.of("content", "Nội dung đã sửa.")).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("Nội dung đã sửa."))
                .andExpect(jsonPath("$.version.status").value("DRAFT")).andExpect(jsonPath("$.version.accepted").doesNotExist());

        // A material change needs the notice period.
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of("effectiveAt", start.plus(Duration.ofDays(5)).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EFFECTIVE_TOO_SOON"));
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of("effectiveAt", start.minus(Duration.ofDays(2)).toString())).andExpect(status().isBadRequest());
        Instant effective = start.plus(Duration.ofDays(16));
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of("effectiveAt", effective.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.version.status").value("SCHEDULED")).andExpect(jsonPath("$.version.contentSha256").value(sha256("Nội dung đã sửa.")));

        // Published text never changes; the version in force is still the old one.
        call(admin, patch("/api/admin/legal-documents/" + draft), Map.of("content", "lén sửa")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_A_DRAFT"));
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of()).andExpect(status().isConflict());
        call(null, get("/api/legal/documents/CUSTOMER_TERMS")).andExpect(jsonPath("$.version").value(1));

        // Another draft of the same kind cannot be published while one waits for its date.
        String second = createDraft("CUSTOMER_TERMS", "Điều khoản khách v3", null, "Bản ba.", false);
        call(admin, post("/api/admin/legal-documents/" + second + "/publish"), Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_ALREADY_SCHEDULED"));

        call(admin, post("/api/admin/legal-documents/" + draft + "/cancel"), Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        call(admin, post("/api/admin/legal-documents/" + draft + "/cancel"), Map.of("reason", "Cần xem lại với cố vấn pháp lý")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version.status").value("CANCELLED")).andExpect(jsonPath("$.version.cancelReason").value("Cần xem lại với cố vấn pháp lý"));
        call(admin, post("/api/admin/legal-documents/" + draft + "/cancel"), Map.of("reason", "lại")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
        // A draft can simply be dropped, and nothing is left waiting.
        call(admin, post("/api/admin/legal-documents/" + second + "/cancel"), Map.of()).andExpect(status().isOk()).andExpect(jsonPath("$.version.status").value("CANCELLED"));
        assertThat(jdbc.sql("select count(*) from legal_document_log where document_id = :d::uuid").param("d", draft).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void aMaterialChangeHoldsBackThoseWhoAcceptedTheOldOneUntilTheyAcceptTheNewOne() throws Exception {
        User sellerUser = newUserEntity("seller", Role.SELLER);
        UUID sellerId = sellerUser.getId();
        String seller = tokenOf(sellerId, Role.SELLER);
        UUID customerId = newUser("customer", Role.CUSTOMER);
        String customer = tokenOf(customerId, Role.CUSTOMER);
        acceptedV1(sellerId, "SELLER_TERMS");
        acceptedV1(sellerId, "PRIVACY_POLICY");
        acceptedV1(customerId, "CUSTOMER_TERMS");
        acceptedV1(customerId, "PRIVACY_POLICY");
        call(seller, get("/api/legal/pending")).andExpect(jsonPath("$.items.length()").value(0));

        String draft = createDraft("SELLER_TERMS", "Điều khoản người bán v2", "Đổi cách tính hoa hồng", "Nội dung bản hai cho người bán.", true);
        Instant effective = start.plus(Duration.ofDays(15));
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of("effectiveAt", effective.toString())).andExpect(status().isOk());
        // They are told when it is published.
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> hasNotification(sellerId, "LEGAL_PUBLISHED"));
        assertThat(hasNotification(customerId, "LEGAL_PUBLISHED")).isFalse();

        // Before the date nothing changes for anyone.
        clock.set(start.plus(Duration.ofDays(14)));
        call(seller, get("/api/notifications")).andExpect(status().isOk());

        // The reminder goes out three days before, once.
        clock.set(start.plus(Duration.ofDays(12)).plus(Duration.ofHours(1)));
        assertThat(legal.remindDue(clock.instant())).isEqualTo(1);
        assertThat(legal.remindDue(clock.instant())).isZero();
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> hasNotification(sellerId, "LEGAL_REMINDER"));

        // From the effective date the seller is stopped with the document to accept; the customer is not.
        clock.set(effective.plus(Duration.ofMinutes(1)));
        call(seller, get("/api/notifications")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEGAL_ACCEPTANCE_REQUIRED"))
                .andExpect(jsonPath("$.documents[0].id").value(draft)).andExpect(jsonPath("$.documents[0].version").value(2))
                .andExpect(jsonPath("$.documents[0].summary").value("Đổi cách tính hoa hồng"));
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        call(seller, get("/api/legal/pending")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(draft));
        // Signing out and reading the document stay possible.
        call(null, get("/api/legal/documents/SELLER_TERMS")).andExpect(jsonPath("$.version").value(2));

        // Accepting something else, or nothing, is refused.
        call(seller, post("/api/legal/accept"), Map.of("documentIds", List.of(UUID.randomUUID().toString()))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        call(seller, post("/api/legal/accept"), Map.of("documentIds", List.of(draft, UUID.randomUUID().toString()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGAL_DOCUMENTS_CHANGED"));
        call(seller, post("/api/legal/accept"), Map.of("documentIds", List.of(draft))).andExpect(status().isNoContent());
        call(seller, get("/api/notifications")).andExpect(status().isOk());
        call(seller, get("/api/legal/pending")).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(jdbc.sql("select count(*) from consent_records where principal_id = :p and document_id = :d::uuid and purpose = 'TERMS' and granted")
                .param("p", sellerId).param("d", draft).query(Long.class).single()).isEqualTo(1);

        // The admin screen sees it was superseded and counts who accepted.
        call(admin, get("/api/admin/legal-documents/" + draft)).andExpect(jsonPath("$.version.status").value("EFFECTIVE")).andExpect(jsonPath("$.version.accepted").value(1));
        call(admin, post("/api/admin/legal-documents/" + draft + "/cancel"), Map.of("reason", "muộn")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_EFFECTIVE"));
        String old = jdbc.sql("select id from legal_documents where type = 'SELLER_TERMS' and version = 1").query(UUID.class).single().toString();
        call(admin, get("/api/admin/legal-documents/" + old)).andExpect(jsonPath("$.version.status").value("SUPERSEDED"));
    }

    @Test
    void aMinorChangeNeverStopsAnyone() throws Exception {
        UUID customerId = newUser("customer", Role.CUSTOMER);
        String customer = tokenOf(customerId, Role.CUSTOMER);
        acceptedV1(customerId, "PRIVACY_POLICY");
        acceptedV1(customerId, "CUSTOMER_TERMS");
        String draft = createDraft("PRIVACY_POLICY", "Chính sách quyền riêng tư v2", "Sửa lỗi chính tả", "Nội dung sửa chính tả.", false);
        // A minor change may take effect at once or on a later date; later, so that no other test sees it.
        Instant effective = start.plus(Duration.ofDays(2));
        call(admin, post("/api/admin/legal-documents/" + draft + "/publish"), Map.of("effectiveAt", effective.toString())).andExpect(status().isOk());
        clock.set(effective.plus(Duration.ofHours(1)));
        call(customer, get("/api/notifications")).andExpect(status().isOk());
        call(customer, get("/api/legal/pending")).andExpect(jsonPath("$.items.length()").value(0));
        call(null, get("/api/legal/documents/PRIVACY_POLICY")).andExpect(jsonPath("$.version").value(2));
        // A minor change gets no reminder.
        clock.set(start);
        assertThat(legal.remindDue(effective.minus(Duration.ofHours(1)))).isZero();
        // Put the old version back in force for the other tests in this run: withdraw is impossible once effective, so move the clock back.
        call(null, get("/api/legal/documents/PRIVACY_POLICY")).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void onlyAdministratorsWithTheWritePermissionMayPublish() throws Exception {
        String customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        String seller = tokenOf(newUser("seller", Role.SELLER), Role.SELLER);
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/legal-documents")).andExpect(status().isForbidden());
            call(token, post("/api/admin/legal-documents"), Map.of("type", "SELLER_TERMS", "title", "x", "content", "x")).andExpect(status().isForbidden());
        }
        call(admin, get("/api/admin/legal-documents")).andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.type=='PRIVACY_POLICY')]").isNotEmpty());
        call(admin, post("/api/admin/legal-documents"), Map.of("type", "SELLER_TERMS", "title", "", "content", "x")).andExpect(status().isBadRequest());
        call(admin, get("/api/admin/legal-documents/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LEGAL_DOCUMENT_NOT_FOUND"));
    }

    // --- helpers

    private String createDraft(String type, String title, String summary, String content, boolean material) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("type", type, "title", title, "content", content, "requiresReacceptance", material));
        if (summary != null) {
            body.put("summary", summary);
        }
        return JsonPath.read(call(admin, post("/api/admin/legal-documents"), body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.version.id");
    }

    private void acceptedV1(UUID principal, String type) {
        UUID doc = jdbc.sql("select id from legal_documents where type = :t and version = 1").param("t", type).query(UUID.class).single();
        jdbc.sql("insert into consent_records (principal_type, principal_id, purpose, document_id, granted, at, channel) values ('USER', :p, 'TERMS', :d, true, now(), 'WEB')")
                .param("p", principal).param("d", doc).update();
    }

    private boolean hasNotification(UUID user, String type) {
        return jdbc.sql("select count(*) from notifications where recipient_id = :u and type = :t").param("u", user).param("t", type).query(Long.class).single() > 0;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private UUID newUser(String kind, Role role) {
        return newUserEntity(kind, role).getId();
    }

    private User newUserEntity(String kind, Role role) {
        User u = new User(kind + "-" + System.nanoTime() + "@example.com", null, kind);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u);
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
        return call(token, request, null);
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
