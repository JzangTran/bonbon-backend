package com.bonbon.backend.support;

import java.time.Instant;
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
import com.bonbon.backend.common.mail.EmailSender;
import com.bonbon.backend.support.service.SupportTicketService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Support tickets: opening with limits, the inbox, answering, replying again, closing and the automatic close (backend#120). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SupportTicketTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

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
    SupportTicketService service;

    @MockitoBean
    EmailSender emailSender;

    String admin;
    String customer;
    UUID customerId;
    String seller;
    UUID vendorId;
    String dishId;
    String addressId;
    String place;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Phiếu", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o").param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = addressFor(customer, lat, lng);
    }

    @Test
    void aTicketIsOpenedAnsweredRepliedToAndClosed() throws Exception {
        String id = open(customer, "Không đăng nhập được", "Mình quên mật khẩu");
        call(customer, get("/api/support/tickets/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].author").value("USER"));

        // The administrators see it, oldest waiting first, and answer it.
        call(admin, get("/api/admin/support/tickets"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id=='" + id + "')]").isNotEmpty());
        call(admin, get("/api/admin/support/tickets/" + id), null).andExpect(jsonPath("$.userName").value("customer")).andExpect(jsonPath("$.audience").value("CUSTOMER"))
                .andExpect(jsonPath("$.messages[0].authorId").value(customerId.toString()));
        call(admin, post("/api/admin/support/tickets/" + id + "/reply"), Map.of("body", "Bạn dùng Quên mật khẩu nhé")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED"));

        // The person sees "support", never which administrator.
        String detail = call(customer, get("/api/support/tickets/" + id), null).andExpect(jsonPath("$.status").value("ANSWERED")).andExpect(jsonPath("$.messages[1].author").value("SUPPORT"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("authorId");
        call(admin, get("/api/admin/support/tickets?status=OPEN"), null).andExpect(jsonPath("$.items[?(@.id=='" + id + "')]").isEmpty());

        // They hear about it in the app and by email (on by default).
        call(customer, get("/api/notifications"), null).andExpect(jsonPath("$.items[0].type").value("SUPPORT_REPLY"));
        ArgumentCaptor<EmailSender.EmailMessage> sent = ArgumentCaptor.forClass(EmailSender.EmailMessage.class);
        Mockito.verify(emailSender, Mockito.timeout(10_000)).send(sent.capture());
        assertThat(sent.getValue().textBody()).contains("Không đăng nhập được").doesNotContain("Quên mật khẩu");

        // Writing again puts it back with the administrators.
        call(customer, post("/api/support/tickets/" + id + "/messages"), Map.of("body", "Cảm ơn, mình làm được rồi")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.messages.length()").value(3));
        call(customer, post("/api/support/tickets/" + id + "/close"), null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.closedBy").value("USER"));
        call(customer, post("/api/support/tickets/" + id + "/messages"), Map.of("body", "Còn nữa")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_CLOSED"));
        call(customer, post("/api/support/tickets/" + id + "/close"), null).andExpect(status().isConflict());
        call(admin, post("/api/admin/support/tickets/" + id + "/reply"), Map.of("body", "x")).andExpect(status().isConflict());
    }

    @Test
    void aPersonHasAtMostThreeUnfinishedTickets() throws Exception {
        String first = open(customer, "Một", "a");
        open(customer, "Hai", "b");
        open(customer, "Ba", "c");
        call(customer, post("/api/support/tickets"), Map.of("subject", "Bốn", "message", "d")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_OPEN_TICKETS"));
        // An answered ticket still counts; a closed one does not.
        call(admin, post("/api/admin/support/tickets/" + first + "/reply"), Map.of("body", "ok")).andExpect(status().isOk());
        call(customer, post("/api/support/tickets"), Map.of("subject", "Bốn", "message", "d")).andExpect(status().isConflict());
        call(customer, post("/api/support/tickets/" + first + "/close"), null).andExpect(status().isOk());
        call(customer, post("/api/support/tickets"), Map.of("subject", "Bốn", "message", "d")).andExpect(status().isCreated());
    }

    @Test
    void aTicketCanPointAtOneOfTheCallersOwnOrdersOnly() throws Exception {
        String order = placeOrder();
        call(customer, post("/api/support/tickets"), Map.of("subject", "Về đơn", "message", "m", "orderId", order)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(order)).andExpect(jsonPath("$.orderNumber").exists());
        // The shop that owns the order may also point at it.
        call(seller, post("/api/support/tickets"), Map.of("subject", "Về đơn của quán", "message", "m", "orderId", order)).andExpect(status().isCreated());
        // Somebody else may not, and the answer does not reveal that the order exists.
        String stranger = tokenOf(newUser("stranger", Role.CUSTOMER), Role.CUSTOMER);
        call(stranger, post("/api/support/tickets"), Map.of("subject", "Đơn người khác", "message", "m", "orderId", order)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        call(stranger, post("/api/support/tickets"), Map.of("subject", "Đơn không có", "message", "m", "orderId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void ticketsArePrivateToTheirOwner() throws Exception {
        String id = open(customer, "Riêng tư", "a");
        String stranger = tokenOf(newUser("stranger", Role.CUSTOMER), Role.CUSTOMER);
        call(stranger, get("/api/support/tickets/" + id), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
        call(stranger, post("/api/support/tickets/" + id + "/messages"), Map.of("body", "xen vào")).andExpect(status().isNotFound());
        call(stranger, post("/api/support/tickets/" + id + "/close"), null).andExpect(status().isNotFound());
        call(stranger, get("/api/support/tickets"), null).andExpect(jsonPath("$.total").value(0));
        call(customer, get("/api/support/tickets"), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(id));
        // The administrators' inbox is for administrators.
        call(customer, get("/api/admin/support/tickets"), null).andExpect(status().isForbidden());
        call(seller, post("/api/admin/support/tickets/" + id + "/reply"), Map.of("body", "x")).andExpect(status().isForbidden());
        call(admin, get("/api/admin/support/tickets/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
        call(admin, get("/api/support/tickets"), null).andExpect(status().isForbidden());
        call(admin, get("/api/admin/support/tickets?status=BOGUS"), null).andExpect(status().isBadRequest());
    }

    @Test
    void attachmentsGoThroughUploadAndBelongToTheirUploader() throws Exception {
        mvc.perform(multipart("/api/support/attachments").file(new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes())).header("Authorization", "Bearer " + customer))
                .andExpect(status().isUnsupportedMediaType());
        String key = JsonPath.read(mvc.perform(multipart("/api/support/attachments").file(new MockMultipartFile("file", "a.png", "image/png", PNG)).header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.attachmentKey");
        assertThat(key).startsWith("support/" + customerId + "/");
        String id = JsonPath.read(call(customer, post("/api/support/tickets"), Map.of("subject", "Có ảnh", "message", "xem ảnh", "attachmentKeys", List.of(key))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages[0].attachments[0].url").exists()).andReturn().getResponse().getContentAsString(), "$.id");
        call(admin, get("/api/admin/support/tickets/" + id), null).andExpect(jsonPath("$.messages[0].attachments[0].key").value(key));
        call(customer, post("/api/support/tickets"), Map.of("subject", "Ảnh người khác", "message", "x", "attachmentKeys", List.of("support/" + UUID.randomUUID() + "/x.png")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ATTACHMENT_INVALID"));
        call(customer, post("/api/support/tickets"), Map.of("subject", "Quá nhiều", "message", "x", "attachmentKeys", List.of("a", "b", "c", "d"))).andExpect(status().isBadRequest());
    }

    @Test
    void anAnsweredTicketNobodyRepliesToIsClosedBySystemAfterTheQuietPeriod() throws Exception {
        String answered = open(customer, "Đã trả lời", "a");
        call(admin, post("/api/admin/support/tickets/" + answered + "/reply"), Map.of("body", "ok")).andExpect(status().isOk());
        String waiting = open(customer, "Đang chờ", "b");
        String recent = open(customer, "Mới trả lời", "c");
        call(admin, post("/api/admin/support/tickets/" + recent + "/reply"), Map.of("body", "ok")).andExpect(status().isOk());

        jdbc.sql("update support_tickets set updated_at = now() - interval '8 days' where id = :i::uuid").param("i", answered).update();
        jdbc.sql("update support_tickets set updated_at = now() - interval '8 days' where id = :i::uuid").param("i", waiting).update();
        jdbc.sql("update support_tickets set updated_at = now() - interval '6 days' where id = :i::uuid").param("i", recent).update();
        service.closeStale(Instant.now());

        call(customer, get("/api/support/tickets/" + answered), null).andExpect(jsonPath("$.status").value("CLOSED")).andExpect(jsonPath("$.closedBy").value("SYSTEM"));
        // A ticket the administrators still owe an answer to is never closed by the clock, and a recent answer is left alone.
        call(customer, get("/api/support/tickets/" + waiting), null).andExpect(jsonPath("$.status").value("OPEN"));
        call(customer, get("/api/support/tickets/" + recent), null).andExpect(jsonPath("$.status").value("ANSWERED"));
    }

    @Test
    void aSellerHasTheirOwnTicketsAndTheValidationHolds() throws Exception {
        String id = open(seller, "Hỏi về hoa hồng", "Hoa hồng tính thế nào?");
        call(admin, get("/api/admin/support/tickets/" + id), null).andExpect(jsonPath("$.audience").value("SHOP"));
        call(admin, post("/api/admin/support/tickets/" + id + "/reply"), Map.of("body", "Xem mục Thu nhập")).andExpect(status().isOk());
        call(seller, get("/api/notifications"), null).andExpect(jsonPath("$.items[?(@.type=='SUPPORT_REPLY')]").isNotEmpty());
        call(customer, get("/api/support/tickets/" + id), null).andExpect(status().isNotFound());

        call(customer, post("/api/support/tickets"), Map.of("subject", "", "message", "x")).andExpect(status().isBadRequest());
        call(customer, post("/api/support/tickets"), Map.of("subject", "x", "message", "m".repeat(2001))).andExpect(status().isBadRequest());
        call(customer, post("/api/support/tickets"), Map.of("subject", "s".repeat(151), "message", "x")).andExpect(status().isBadRequest());
    }

    // --- helpers

    private String open(String token, String subject, String message) throws Exception {
        return JsonPath.read(call(token, post("/api/support/tickets"), Map.of("subject", subject, "message", message)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String placeOrder() throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD", "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-ticket-" + System.nanoTime()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String addressFor(String token, double lat, double lng) throws Exception {
        String id = JsonPath.read(call(token, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Khách",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", id).update();
        return id;
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
