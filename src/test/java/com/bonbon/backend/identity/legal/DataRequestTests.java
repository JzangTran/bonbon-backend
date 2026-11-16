package com.bonbon.backend.identity.legal;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.identity.authentication.Role;
import com.bonbon.backend.identity.authentication.entity.User;
import com.bonbon.backend.identity.authentication.repository.UserRepository;
import com.bonbon.backend.identity.authentication.service.TokenService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data-subject requests (data-subject-requests.md): a person downloads a copy of their data at once and asks an administrator for the
 * rest; the administrator starts, extends once, completes or rejects with a reason, and the person is told.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DataRequestTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    UUID customerId;
    String customer;
    String admin;

    @BeforeEach
    void setUp() {
        customerId = newUser("Khách Dữ Liệu", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        admin = tokenOf(newUser("Quản Trị Dữ Liệu", Role.ADMIN), Role.ADMIN);
    }

    @Test
    void aPersonDownloadsTheirDataAtOnceButOnlyOnceAnHour() throws Exception {
        String body = call(customer, post("/api/me/data-export"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ACCESS"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.downloadUrl");
        HttpResponse<String> file = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(file.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> json = JSON.readValue(file.body(), Map.class);
        assertThat(json).containsKeys("generatedAt", "account", "orders", "messagesSent");
        assertThat(file.body()).contains("Khách Dữ Liệu").doesNotContain("password");

        call(customer, post("/api/me/data-export"), null).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("EXPORT_TOO_SOON"));
        call(customer, get("/api/me/data-requests"), null).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].downloadUrl").exists());
    }

    @Test
    void aRequestIsQueuedWithDeadlinesAndAtMostThreeStayOpen() throws Exception {
        String id = JsonPath.read(call(customer, post("/api/me/data-requests"), Map.of("type", "CORRECT", "details", "Tên trên đơn cũ bị sai chính tả, xin sửa lại."))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.ackDueAt").exists())
                .andExpect(jsonPath("$.dueAt").exists())
                .andReturn().getResponse().getContentAsString(), "$.id");
        Instant received = jdbc.sql("select received_at from identity.data_subject_requests where id = cast(:i as uuid)").param("i", id)
                .query(java.sql.Timestamp.class).single().toInstant();
        Instant due = jdbc.sql("select due_at from identity.data_subject_requests where id = cast(:i as uuid)").param("i", id)
                .query(java.sql.Timestamp.class).single().toInstant();
        assertThat(java.time.Duration.between(received, due).toDays()).isEqualTo(10);

        call(customer, post("/api/me/data-requests"), Map.of("type", "DELETE", "details", "Xoá giúp toàn bộ dữ liệu của tôi."))
                .andExpect(status().isBadRequest());
        call(customer, post("/api/me/data-requests"), Map.of("type", "OBJECT", "details", "ngắn")).andExpect(status().isBadRequest());
        call(customer, post("/api/me/data-requests"), Map.of("type", "WITHDRAW", "details", "Rút lại đồng ý nhận tin khuyến mãi.")).andExpect(status().isCreated());
        call(customer, post("/api/me/data-requests"), Map.of("type", "RESTRICT", "details", "Tạm ngưng dùng địa chỉ của tôi để gợi ý.")).andExpect(status().isCreated());
        call(customer, post("/api/me/data-requests"), Map.of("type", "OBJECT", "details", "Phản đối dùng lịch sử đơn để xếp hạng quán."))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TOO_MANY_OPEN_REQUESTS"));

        call(admin, get("/api/admin/data-requests?size=100"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + id + "')].email").exists());
        call(admin, get("/api/admin/data-requests?status=NOPE"), null).andExpect(jsonPath("$.code").value("INVALID_STATUS"));
        call(customer, get("/api/admin/data-requests"), null).andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorStartsExtendsOnceAndClosesWithAReason() throws Exception {
        String id = JsonPath.read(call(customer, post("/api/me/data-requests"), Map.of("type", "OBJECT", "details", "Phản đối dùng lịch sử đơn để gợi ý món."))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String path = "/api/admin/data-requests/" + id;

        call(admin, patch(path), Map.of("action", "START")).andExpect(status().isNoContent());
        call(admin, patch(path), Map.of("action", "EXTEND", "extendDays", 10)).andExpect(jsonPath("$.code").value("EXTENSION_REASON_REQUIRED"));
        call(admin, patch(path), Map.of("action", "EXTEND", "extendDays", 10, "note", "Cần đối chiếu với nhiều quán.")).andExpect(status().isNoContent());
        call(admin, patch(path), Map.of("action", "EXTEND", "extendDays", 5, "note", "Thêm lần nữa")).andExpect(jsonPath("$.code").value("ALREADY_EXTENDED"));
        call(admin, patch(path), Map.of("action", "REJECT")).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        call(admin, patch(path), Map.of("action", "REJECT", "note", "Dữ liệu giao dịch phải lưu theo luật kế toán.")).andExpect(status().isNoContent());
        call(admin, patch(path), Map.of("action", "COMPLETE")).andExpect(jsonPath("$.code").value("DATA_REQUEST_CLOSED"));
        call(admin, patch("/api/admin/data-requests/" + UUID.randomUUID()), Map.of("action", "START")).andExpect(status().isNotFound());

        call(customer, get("/api/me/data-requests"), null)
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[0].extended").value(true))
                .andExpect(jsonPath("$[0].responseNote").value("Dữ liệu giao dịch phải lưu theo luật kế toán."));
        assertThat(jdbc.sql("select count(*) from notifications where recipient_id = :u and type = 'LEGAL_DATA_REQUEST'").param("u", customerId)
                .query(Long.class).single()).isEqualTo(1);
    }

    private UUID newUser(String name, Role role) {
        User u = new User("dsr-" + System.nanoTime() + "@example.com", null, name);
        u.addRole(role);
        u.markEmailVerified();
        return users.saveAndFlush(u).getId();
    }

    private String tokenOf(UUID userId, Role role) {
        return tokens.issue(users.findById(userId).orElseThrow(), role, Instant.now()).accessToken();
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
