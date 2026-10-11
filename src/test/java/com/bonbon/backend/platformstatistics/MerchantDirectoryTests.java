package com.bonbon.backend.platformstatistics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Finding shops, seeing one in full, and seeing where the platform has coverage (backend#129). Shops are written straight to the database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MerchantDirectoryTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    String admin;
    String province;
    String tag;

    @BeforeEach
    void setUp() {
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
        tag = Long.toString(System.nanoTime(), 36);
        province = "Tỉnh Thử " + tag;
    }

    @Test
    void theDirectoryListsApprovedShopsByDefaultWithTheirStanding() throws Exception {
        UUID id = shop("Quán Danh Bạ " + tag, "APPROVED", "Phường A", new BigDecimal("1.5"));
        jdbc.sql("update vendors set rating_sum = 9, rating_count = 2, commission_stage = 'OVERDUE' where id = :id").param("id", id).update();
        jdbc.sql("""
                insert into shop_penalties (vendor_id, points, source, reason, status, issued_at, expires_at) values (:v, 2, 'MANUAL', 'thử', 'ACTIVE', now(), now() + interval '30 days'),
                       (:v, 1, 'MANUAL', 'đã miễn', 'WAIVED', now(), now() + interval '30 days'), (:v, 5, 'MANUAL', 'hết hạn', 'ACTIVE', now() - interval '60 days', now() - interval '30 days')""")
                .param("v", id).update();
        jdbc.sql("""
                insert into merchant_suspensions (vendor_id, kind, status, reason, notice_sent_at, effective_at, created_by_id, created_at)
                values (:v, 'SCHEDULED', 'SCHEDULED', 'thử', now(), now() + interval '6 days', :a, now())""")
                .param("v", id).param("a", jdbc.sql("select id from users limit 1").query(UUID.class).single()).update();
        UUID pending = shop("Quán Chờ Duyệt " + tag, "PENDING", "Phường A", new BigDecimal("1.0"));

        call(admin, get("/api/admin/merchants").param("q", "danh ba " + tag)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].vendorId").value(id.toString())).andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].ratingAverage").value(4.5)).andExpect(jsonPath("$.items[0].ratingCount").value(2))
                .andExpect(jsonPath("$.items[0].activePoints").value(2)).andExpect(jsonPath("$.items[0].commissionStage").value("OVERDUE"))
                .andExpect(jsonPath("$.items[0].suspension").value("SCHEDULED")).andExpect(jsonPath("$.items[0].deliveredOrders").value(0));
        call(admin, get("/api/admin/merchants").param("q", "cho duyet " + tag)).andExpect(jsonPath("$.total").value(0));
        call(admin, get("/api/admin/merchants").param("status", "PENDING").param("q", "cho duyet " + tag)).andExpect(jsonPath("$.items[0].vendorId").value(pending.toString()));
        call(admin, get("/api/admin/merchants").param("status", "ALL").param("q", tag)).andExpect(jsonPath("$.total").value(2));
        call(admin, get("/api/admin/merchants").param("q", "phuong a").param("status", "ALL").param("size", "1")).andExpect(jsonPath("$.items.length()").value(1));
        call(admin, get("/api/admin/merchants").param("status", "NOPE")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STATUS_FILTER_INVALID"));
        call(admin, get("/api/admin/merchants").param("size", "500")).andExpect(status().isBadRequest());
    }

    @Test
    void coverageFlagsAShopWhoseRadiusIsFarAboveItsProvinceNorm() throws Exception {
        shop("Quán Một " + tag, "APPROVED", "Phường A", new BigDecimal("1.0"));
        shop("Quán Hai " + tag, "APPROVED", "Phường A", new BigDecimal("1.0"));
        shop("Quán Ba " + tag, "APPROVED", "Phường B", new BigDecimal("1.2"));
        UUID wide = shop("Quán Rộng " + tag, "APPROVED", "Phường B", new BigDecimal("5.0"));
        shop("Quán Đã Đóng " + tag, "SUSPENDED", "Phường B", new BigDecimal("9.0"));

        String all = call(admin, get("/api/admin/merchants/coverage").param("province", province.toUpperCase())).andExpect(status().isOk())
                .andExpect(jsonPath("$.outlierFactor").value(2.0)).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[?(@.outlier == true)].vendorId").value(wide.toString()))
                .andExpect(jsonPath("$.items[?(@.vendorId=='" + wide + "')].provinceMedianRadiusKm").value(1.1)).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(all).doesNotContain("Đã Đóng");
        call(admin, get("/api/admin/merchants/coverage").param("province", province).param("ward", "phuong a")).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.outlier == true)]").isEmpty());
        call(admin, get("/api/admin/merchants/coverage").param("province", "Không có tỉnh này")).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void theDetailShowsTheShopItsHistoryAndTheShopsThatLookLikeIt() throws Exception {
        UUID one = shop("Quán Gốc " + tag, "APPROVED", "Phường A", new BigDecimal("1.0"));
        UUID same = shop("Quán Trùng Thuế " + tag, "PENDING", "Phường A", new BigDecimal("1.0"));
        UUID person = shop("Quán Cùng Người " + tag, "REJECTED", "Phường A", new BigDecimal("1.0"));
        UUID other = shop("Quán Khác " + tag, "APPROVED", "Phường A", new BigDecimal("1.0"));
        for (UUID v : List.of(one, same)) {
            jdbc.sql("insert into vendor_tax_info (vendor_id, tax_code) values (:v, :t)").param("v", v).param("t", "TAX-" + tag).update();
        }
        jdbc.sql("insert into vendor_tax_info (vendor_id, tax_code) values (:v, :t)").param("v", other).param("t", "OTHER-" + tag).update();
        for (UUID v : List.of(one, person)) {
            jdbc.sql("insert into vendor_identity (vendor_id, doc_type, doc_number_last4, full_name) values (:v, 'CCCD', '4321', :n)").param("v", v).param("n", v == one ? "Nguyễn Văn A" : "NGUYỄN VĂN A").update();
        }
        jdbc.sql("insert into vendor_identity (vendor_id, doc_type, doc_number_last4, full_name) values (:v, 'CCCD', '4321', 'Trần Thị B')").param("v", other).update();
        jdbc.sql("""
                insert into merchant_suspensions (vendor_id, kind, status, reason, notice_sent_at, effective_at, created_by_id, created_at, ended_at, end_reason)
                values (:v, 'SCHEDULED', 'LIFTED', 'lần trước', now() - interval '20 days', now() - interval '15 days', :a, now() - interval '20 days', now() - interval '10 days', 'đã khắc phục')""")
                .param("v", one).param("a", jdbc.sql("select id from users limit 1").query(UUID.class).single()).update();

        call(admin, get("/api/admin/merchants/" + one)).andExpect(status().isOk()).andExpect(jsonPath("$.shop.name").value("Quán Gốc " + tag))
                .andExpect(jsonPath("$.shop.status").value("APPROVED")).andExpect(jsonPath("$.suspensions.length()").value(1)).andExpect(jsonPath("$.suspensions[0].endReason").value("đã khắc phục"))
                .andExpect(jsonPath("$.duplicates.length()").value(2))
                .andExpect(jsonPath("$.duplicates[?(@.vendorId=='" + same + "')].reason").value("TAX_CODE"))
                .andExpect(jsonPath("$.duplicates[?(@.vendorId=='" + person + "')].reason").value("POSSIBLE_SAME_PERSON"));
        // The documents themselves are not part of this view.
        call(admin, get("/api/admin/merchants/" + other)).andExpect(jsonPath("$.duplicates.length()").value(0));

        UUID draft = shop("Quán Nháp " + tag, "DRAFT", "Phường A", new BigDecimal("1.0"));
        call(admin, get("/api/admin/merchants/" + draft)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_APPLICATION_NOT_FOUND"));
        call(admin, get("/api/admin/merchants/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void onlyAdministratorsWithTheReadPermissionMayLook() throws Exception {
        String customer = tokenOf(newUser("customer", Role.CUSTOMER), Role.CUSTOMER);
        String seller = tokenOf(newUser("seller", Role.SELLER), Role.SELLER);
        for (String token : List.of(customer, seller)) {
            call(token, get("/api/admin/merchants")).andExpect(status().isForbidden());
            call(token, get("/api/admin/merchants/coverage")).andExpect(status().isForbidden());
            call(token, get("/api/admin/merchants/" + UUID.randomUUID())).andExpect(status().isForbidden());
        }
    }

    // --- helpers

    private UUID shop(String name, String status, String ward, BigDecimal radius) {
        UUID owner = newUser("owner", Role.SELLER);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into vendors (id, owner_user_id, name, status, province, ward, formatted_address, lat, lng, delivery_radius_km, delivery_fee, created_at, updated_at)
                values (:id, :o, :n, :s, :p, :w, :a, 10.78, 106.7, :r, 10000, now(), now())""")
                .param("id", id).param("o", owner).param("n", name).param("s", status).param("p", province).param("w", ward).param("a", "Số 1, " + ward + ", " + province).param("r", radius).update();
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

    private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }
}
