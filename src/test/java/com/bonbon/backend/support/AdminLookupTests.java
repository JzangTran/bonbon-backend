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
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

/** The administrators' lookup of orders, customers and conversations: exact identifiers, masking, reveal with a reason, audit (backend#117). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdminLookupTests {

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

    String seller;
    UUID vendorId;
    String dishId;
    String customer;
    UUID customerId;
    String customerEmail;
    String customerPhone;
    String addressId;
    String place;
    String admin;
    UUID adminId;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;
        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Tra Cứu", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm", "price", 40000))
                .andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");
        customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        customerPhone = "09" + String.format("%08d", System.nanoTime() % 100_000_000);
        jdbc.sql("update users set phone = :p where id = :id").param("p", customerPhone).param("id", customerId).update();
        customerEmail = jdbc.sql("select email from users where id = :id").param("id", customerId).query(String.class).single();
        addressId = addressFor(customer, lat, lng);
        adminId = newUser("admin", Role.ADMIN);
        admin = tokenOf(adminId, Role.ADMIN);
    }

    @Test
    void anOrderIsFoundByItsCodeAndOpenedWithEverythingAboutIt() throws Exception {
        String id = place(2);
        long number = jdbc.sql("select number from orders where id = :i::uuid").param("i", id).query(Long.class).single();
        call(admin, get("/api/admin/orders").param("code", "#" + number), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].status").value("PLACED"));

        deliver(id);
        call(customer, post("/api/orders/" + id + "/report-not-received"), Map.of("note", "chưa thấy")).andExpect(status().isCreated());
        call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", "Đơn của mình đâu?")).andExpect(status().isCreated());

        var detail = call(admin, get("/api/admin/orders/" + id), null).andExpect(status().isOk()).andExpect(jsonPath("$.number").value(number))
                .andExpect(jsonPath("$.status").value("DELIVERED")).andExpect(jsonPath("$.items[0].name").value("Cơm")).andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.grandTotal").value(90000)).andExpect(jsonPath("$.payment.method").value("COD"))
                .andExpect(jsonPath("$.timeline[0].to").value("PLACED")).andExpect(jsonPath("$.timeline[0].by").value("CUSTOMER"))
                .andExpect(jsonPath("$.timeline[1].by").value("SHOP")).andExpect(jsonPath("$.cases.length()").value(1))
                .andExpect(jsonPath("$.cases[0].type").value("NOT_RECEIVED")).andExpect(jsonPath("$.conversationId").exists()).andReturn().getResponse().getContentAsString();

        // Contact details are masked: the phone keeps two digits at each end, the street goes, the email keeps its first letter.
        String phone = JsonPath.read(detail, "$.customer.phone");
        assertThat(phone).isEqualTo(customerPhone.substring(0, 2) + "*".repeat(customerPhone.length() - 4) + customerPhone.substring(customerPhone.length() - 2));
        assertThat((String) JsonPath.read(detail, "$.customer.email")).startsWith(customerEmail.substring(0, 1) + "***@");
        assertThat((String) JsonPath.read(detail, "$.deliveryPhone")).contains("*");
        assertThat((String) JsonPath.read(detail, "$.deliveryAddress")).startsWith("***");
        assertThat(detail).doesNotContain(customerPhone).doesNotContain(customerEmail);
    }

    @Test
    void theConversationOfAnOrderCanBeReadOnlyWithItsOwnPermissionAndEachOpeningIsAudited() throws Exception {
        String id = place(1);
        call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", "Cho mình đổi địa chỉ")).andExpect(status().isCreated());
        String conversation = JsonPath.read(call(admin, get("/api/admin/orders/" + id), null).andReturn().getResponse().getContentAsString(), "$.conversationId");

        call(admin, get("/api/admin/conversations/" + conversation), null).andExpect(status().isOk()).andExpect(jsonPath("$.customerName").value("customer"));
        assertThat(audits("READ_CONVERSATION", conversation)).isZero();
        call(admin, get("/api/admin/conversations/" + conversation + "/messages"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].text").value("Cho mình đổi địa chỉ"))
                .andExpect(jsonPath("$.items[0].mine").value(false));
        assertThat(audits("READ_CONVERSATION", conversation)).isEqualTo(1);
        // Reading an older page of the same conversation is the same opening.
        call(admin, get("/api/admin/conversations/" + conversation + "/messages?before=" + Instant.now().plusSeconds(5)), null).andExpect(status().isOk());
        assertThat(audits("READ_CONVERSATION", conversation)).isEqualTo(1);

        call(customer, get("/api/admin/conversations/" + conversation + "/messages"), null).andExpect(status().isForbidden());
        call(seller, get("/api/admin/conversations/" + conversation + "/messages"), null).andExpect(status().isForbidden());
        call(admin, get("/api/admin/conversations/" + UUID.randomUUID() + "/messages"), null).andExpect(status().isNotFound());
    }

    @Test
    void aSearchNeedsExactlyOneIdentifierAndIsCapped() throws Exception {
        call(admin, get("/api/admin/orders"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDENTIFIER_REQUIRED"));
        call(admin, get("/api/admin/orders?code=1&customerEmail=a@b.c"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDENTIFIER_REQUIRED"));
        call(admin, get("/api/admin/orders?code=abc"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CODE_INVALID"));
        call(admin, get("/api/admin/customers"), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDENTIFIER_REQUIRED"));
        call(admin, get("/api/admin/customers?email=a@b.c&phone=0123"), null).andExpect(status().isBadRequest());
        call(admin, get("/api/admin/orders?code=999999999"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));

        String first = place(1);
        for (int i = 0; i < 21; i++) {
            jdbc.sql("""
                    insert into orders (customer_id, vendor_id, vendor_name, status, payment_method, payment_status, delivery_name, delivery_phone, delivery_address,
                                        delivery_lat, delivery_lng, items_total, delivery_fee, grand_total, idempotency_key)
                    select customer_id, vendor_id, vendor_name, 'DELIVERED', payment_method, payment_status, delivery_name, delivery_phone, delivery_address,
                           delivery_lat, delivery_lng, items_total, delivery_fee, grand_total, 'clone-' || :i || '-' || id from orders where id = :o::uuid""")
                    .param("i", i).param("o", first).update();
        }
        call(admin, get("/api/admin/orders?customerEmail=" + customerEmail.toUpperCase()), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20));
        call(admin, get("/api/admin/orders?customerPhone=" + customerPhone), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20));
        // Partial values find nothing: only an exact match counts.
        call(admin, get("/api/admin/orders?customerEmail=" + customerEmail.substring(0, 8)), null).andExpect(jsonPath("$.items.length()").value(0));
        call(admin, get("/api/admin/customers?phone=" + customerPhone.substring(0, 6)), null).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void aCustomerIsFoundMaskedAndNeverShowsSecrets() throws Exception {
        place(1);
        var found = call(admin, get("/api/admin/customers?email=" + customerEmail), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(customerId.toString())).andExpect(jsonPath("$.items[0].roles[0]").value("CUSTOMER")).andReturn().getResponse().getContentAsString();
        assertThat(found).doesNotContain(customerEmail).doesNotContain(customerPhone);

        String detail = call(admin, get("/api/admin/customers/" + customerId), null).andExpect(status().isOk()).andExpect(jsonPath("$.recentOrders.length()").value(1))
                .andExpect(jsonPath("$.emailVerified").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(customerEmail).doesNotContain(customerPhone).doesNotContain("password").doesNotContain("token");

        // An administrator account is never a "customer".
        String adminEmail = jdbc.sql("select email from users where id = :id").param("id", adminId).query(String.class).single();
        call(admin, get("/api/admin/customers?email=" + adminEmail), null).andExpect(jsonPath("$.items.length()").value(0));
        call(admin, get("/api/admin/customers/" + adminId), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void revealingNeedsAReasonAndIsAudited() throws Exception {
        String id = place(1);
        call(admin, post("/api/admin/orders/" + id + "/reveal"), Map.of("reason", "CURIOSITY")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/orders/" + id + "/reveal"), Map.of("reason", "OTHER")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        assertThat(audits("REVEAL_ORDER", id)).isZero();

        call(admin, post("/api/admin/orders/" + id + "/reveal"), Map.of("reason", "DISPUTE")).andExpect(status().isOk()).andExpect(jsonPath("$.customerEmail").value(customerEmail))
                .andExpect(jsonPath("$.customerPhone").value(customerPhone)).andExpect(jsonPath("$.deliveryPhone").value("0987654321")).andExpect(jsonPath("$.deliveryAddress").exists());
        call(admin, post("/api/admin/customers/" + customerId + "/reveal"), Map.of("reason", "OTHER", "note", "Khách gọi hỏi")).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(customerEmail)).andExpect(jsonPath("$.phone").value(customerPhone));
        assertThat(audits("REVEAL_ORDER", id)).isEqualTo(1);
        assertThat(jdbc.sql("select reason from admin_lookup_audit where subject_id = :s and action = 'REVEAL_ORDER'").param("s", UUID.fromString(id)).query(String.class).single()).isEqualTo("DISPUTE");
        assertThat(jdbc.sql("select reason_note from admin_lookup_audit where subject_id = :s and action = 'REVEAL_CUSTOMER'").param("s", customerId).query(String.class).single())
                .isEqualTo("Khách gọi hỏi");
    }

    @Test
    void everySearchAndOpeningIsAudited() throws Exception {
        String id = place(1);
        long before = jdbc.sql("select count(*) from admin_lookup_audit where admin_id = :a").param("a", adminId).query(Long.class).single();
        call(admin, get("/api/admin/orders?customerEmail=" + customerEmail), null).andExpect(status().isOk());
        call(admin, get("/api/admin/orders/" + id), null).andExpect(status().isOk());
        call(admin, get("/api/admin/customers/" + customerId), null).andExpect(status().isOk());
        call(admin, get("/api/admin/customers?phone=" + customerPhone), null).andExpect(status().isOk());
        assertThat(jdbc.sql("select count(*) from admin_lookup_audit where admin_id = :a").param("a", adminId).query(Long.class).single()).isEqualTo(before + 4);
        assertThat(jdbc.sql("select query from admin_lookup_audit where admin_id = :a and action = 'SEARCH_ORDERS'").param("a", adminId).query(String.class).single()).isEqualTo(customerEmail);
        assertThat(jdbc.sql("select result_count from admin_lookup_audit where admin_id = :a and action = 'SEARCH_CUSTOMERS'").param("a", adminId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void onlyAdministratorsMayLookThingsUp() throws Exception {
        String id = place(1);
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/orders?code=1"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/orders/" + id), null).andExpect(status().isForbidden());
            call(token, post("/api/admin/orders/" + id + "/reveal"), Map.of("reason", "DISPUTE")).andExpect(status().isForbidden());
            call(token, get("/api/admin/customers?email=a@b.c"), null).andExpect(status().isForbidden());
            call(token, get("/api/admin/customers/" + customerId), null).andExpect(status().isForbidden());
        }
        call(admin, get("/api/admin/orders/" + UUID.randomUUID()), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    // --- helpers

    private long audits(String action, String subject) {
        return jdbc.sql("select count(*) from admin_lookup_audit where action = :a and subject_id = :s::uuid").param("a", action).param("s", subject).query(Long.class).single();
    }

    private String place(int quantity) throws Exception {
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", quantity)));
        return JsonPath.read(call(customer, post("/api/orders"), body, "key-lookup-" + System.nanoTime()).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void deliver(String id) throws Exception {
        call(seller, post("/api/merchant/orders/" + id + "/confirm"), null).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "PREPARING")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "OUT_FOR_DELIVERY")).andExpect(status().isOk());
        call(seller, post("/api/merchant/orders/" + id + "/status"), Map.of("to", "DELIVERED")).andExpect(status().isOk());
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
