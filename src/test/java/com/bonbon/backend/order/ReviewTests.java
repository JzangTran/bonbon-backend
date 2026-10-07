package com.bonbon.backend.order;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reviews of delivered orders, the shop's reply and admin moderation (backend#75). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReviewTests {

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
    String addressId;
    String admin;
    int keySeq;

    @BeforeEach
    void setUp() throws Exception {
        String leaf = jdbc.sql("select id from categories where level = 3 and active order by name limit 1").query(UUID.class).single().toString();
        double lat = -80 + (System.nanoTime() % 1000) * 0.01;
        double lng = 151.2;

        UUID sellerId = newUser("seller", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(seller, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Đánh Giá", "phone", "0912345678", "email", "q@example.com", "placeId", place));
        call(seller, put("/api/merchant/shop/steps/2"), Map.of("openingHours", allWeek(), "deliveryRadiusKm", 2, "deliveryFee", 10000));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now(), lat = :la, lng = :ln where owner_user_id = :o")
                .param("la", lat).param("ln", lng).param("o", sellerId).update();
        vendorId = jdbc.sql("select id from vendors where owner_user_id = :o").param("o", sellerId).query(UUID.class).single();
        String section = JsonPath.read(call(seller, post("/api/merchant/menu-sections"), Map.of("name", "Món")).andReturn().getResponse().getContentAsString(), "$.sections[0].id");
        dishId = JsonPath.read(call(seller, post("/api/merchant/menu-items"), Map.of("sectionId", section, "categoryId", leaf, "name", "Cơm sườn",
                "price", 40000, "stockQuantity", 50)).andReturn().getResponse().getContentAsString(), "$.sections[0].items[0].id");

        UUID customerId = newUser("customer", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        addressId = JsonPath.read(call(customer, post("/api/account/addresses"), Map.of("label", "Nhà", "placeId", place, "recipientName", "Trần Văn An",
                "recipientPhone", "0987654321", "makeDefault", true)).andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.sql("update addresses set lat = :la, lng = :ln where id = :i::uuid").param("la", lat + 0.003).param("ln", lng).param("i", addressId).update();
        admin = tokenOf(newUser("admin", Role.ADMIN), Role.ADMIN);
    }

    @Test
    void onlyADeliveredOrderOfTheCallerCanBeReviewedOnce() throws Exception {
        String id = place(customer);
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 5)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_ALLOWED"));
        deliver(id);

        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 6)).andExpect(status().isBadRequest());
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 0)).andExpect(status().isBadRequest());
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 4, "comment", "  Món ngon, giao nhanh  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating").value(4)).andExpect(jsonPath("$.comment").value("Món ngon, giao nhanh"))
                .andExpect(jsonPath("$.reviewerName").value("An T.")).andExpect(jsonPath("$.editableUntil").exists())
                .andExpect(jsonPath("$.hidden").value(false));
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 5)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));

        String other = tokenOf(newUser("customer2", Role.CUSTOMER), Role.CUSTOMER);
        call(other, post("/api/orders/" + id + "/review"), Map.of("rating", 1)).andExpect(status().isNotFound());
        call(other, get("/api/orders/" + id + "/review"), null).andExpect(status().isNotFound());
        call(seller, post("/api/orders/" + id + "/review"), Map.of("rating", 5)).andExpect(status().isForbidden());

        call(customer, get("/api/orders/" + id), null).andExpect(jsonPath("$.review.rating").value(4)).andExpect(jsonPath("$.review.hidden").value(false));
        call(customer, get("/api/orders/" + id + "/review"), null).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(4));
    }

    @Test
    void theShopAverageFollowsPostingEditingAndDeleting() throws Exception {
        assertThat(menuRating()).isNull();
        String first = deliveredOrder();
        String second = deliveredOrder();
        call(customer, post("/api/orders/" + first + "/review"), Map.of("rating", 5)).andExpect(status().isCreated());
        call(customer, post("/api/orders/" + second + "/review"), Map.of("rating", 2)).andExpect(status().isCreated());
        assertThat(menuRating()).isEqualTo(3.5);
        assertThat(jdbc.sql("select rating_count from vendors where id = :v").param("v", vendorId).query(Integer.class).single()).isEqualTo(2);

        call(customer, put("/api/orders/" + second + "/review"), Map.of("rating", 3, "comment", "Tạm ổn")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(3));
        assertThat(menuRating()).isEqualTo(4.0);

        call(customer, delete("/api/orders/" + first + "/review"), null).andExpect(status().isNoContent());
        assertThat(menuRating()).isEqualTo(3.0);
        call(customer, get("/api/orders/" + first + "/review"), null).andExpect(status().isNotFound());
        // A deleted review can be written again.
        call(customer, post("/api/orders/" + first + "/review"), Map.of("rating", 1)).andExpect(status().isCreated());
        assertThat(menuRating()).isEqualTo(2.0);

        // The shop list carries the same figures.
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.ratingAverage").value(2.0)).andExpect(jsonPath("$.ratingCount").value(2));
    }

    @Test
    void theEditWindowClosesAfterTwentyFourHours() throws Exception {
        String id = deliveredOrder();
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 5)).andExpect(status().isCreated());
        jdbc.sql("update reviews set created_at = :t where order_id = :o::uuid").param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(25 * 3600)))
                .param("o", id).update();
        call(customer, put("/api/orders/" + id + "/review"), Map.of("rating", 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_LOCKED"));
        call(customer, delete("/api/orders/" + id + "/review"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_LOCKED"));
        assertThat(menuRating()).isEqualTo(5.0);
    }

    @Test
    void thePublicListShowsShortNamesAndNothingAboutTheOrder() throws Exception {
        String id = deliveredOrder();
        call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 5, "comment", "Ngon")).andExpect(status().isCreated());
        // No token needed.
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewerName").value("An T.")).andExpect(jsonPath("$.items[0].comment").value("Ngon"))
                .andExpect(jsonPath("$.items[0].orderId").doesNotExist()).andExpect(jsonPath("$.items[0].orderNumber").doesNotExist())
                .andExpect(jsonPath("$.items[0].vendorId").doesNotExist()).andExpect(jsonPath("$.items[0].hidden").doesNotExist());
        call(null, get("/api/vendors/" + UUID.randomUUID() + "/reviews"), null).andExpect(status().isNotFound());
        call(null, get("/api/vendors/" + vendorId + "/reviews?size=500"), null).andExpect(status().isBadRequest());
    }

    @Test
    void theShopRepliesOnceAndCanEditWithinTheWindow() throws Exception {
        String id = deliveredOrder();
        String review = JsonPath.read(call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 2, "comment", "Hơi nguội")).andReturn()
                .getResponse().getContentAsString(), "$.id");

        call(seller, get("/api/merchant/reviews?unreplied=true"), null).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].orderNumber").exists()).andExpect(jsonPath("$.items[0].reply").doesNotExist());
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", " ")).andExpect(status().isBadRequest());
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Xin lỗi bạn, quán sẽ rút kinh nghiệm")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reply.text").value("Xin lỗi bạn, quán sẽ rút kinh nghiệm")).andExpect(jsonPath("$.reply.editableUntil").exists());
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Lần hai")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_RESPONDED"));
        call(seller, get("/api/merchant/reviews?unreplied=true"), null).andExpect(jsonPath("$.total").value(0));
        call(seller, get("/api/merchant/reviews"), null).andExpect(jsonPath("$.total").value(1));

        call(customer, get("/api/orders/" + id + "/review"), null).andExpect(jsonPath("$.reply.text").value("Xin lỗi bạn, quán sẽ rút kinh nghiệm"));
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(jsonPath("$.items[0].reply.text").value("Xin lỗi bạn, quán sẽ rút kinh nghiệm"))
                .andExpect(jsonPath("$.items[0].reply.id").doesNotExist());

        call(seller, put("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Cảm ơn góp ý, quán đã nhắc nhở bếp")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply.text").value("Cảm ơn góp ý, quán đã nhắc nhở bếp"));

        jdbc.sql("update review_responses set created_at = :t where review_id = :r::uuid").param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(25 * 3600)))
                .param("r", review).update();
        call(seller, put("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Muộn")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_LOCKED"));
        call(seller, delete("/api/merchant/reviews/" + review + "/response"), null).andExpect(status().isConflict());
    }

    @Test
    void aReplyCanBeDeletedAndWrittenAgain() throws Exception {
        String id = deliveredOrder();
        String review = JsonPath.read(call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 3)).andReturn().getResponse().getContentAsString(), "$.id");
        call(seller, delete("/api/merchant/reviews/" + review + "/response"), null).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESPONSE_NOT_FOUND"));
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Cảm ơn")).andExpect(status().isCreated());
        call(seller, delete("/api/merchant/reviews/" + review + "/response"), null).andExpect(status().isNoContent());
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Cảm ơn bạn")).andExpect(status().isCreated());
    }

    @Test
    void aShopOnlySeesAndAnswersItsOwnReviews() throws Exception {
        String id = deliveredOrder();
        String review = JsonPath.read(call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 1)).andReturn().getResponse().getContentAsString(), "$.id");

        UUID otherId = newUser("seller2", Role.SELLER);
        String other = tokenOf(otherId, Role.SELLER);
        String place = geocoder.autocomplete("Toà S2", null, null).get(0).placeId();
        call(other, put("/api/merchant/shop/steps/1"), Map.of("name", "Quán Khác", "phone", "0912345678", "email", "k@example.com", "placeId", place));
        jdbc.sql("update vendors set status = 'APPROVED', decided_at = now() where owner_user_id = :o").param("o", otherId).update();

        call(other, get("/api/merchant/reviews"), null).andExpect(jsonPath("$.total").value(0));
        call(other, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Không phải của tôi")).andExpect(status().isNotFound());
        call(customer, get("/api/merchant/reviews"), null).andExpect(status().isForbidden());
        call(customer, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "x")).andExpect(status().isForbidden());
    }

    @Test
    void anAdminHidesAReviewAndTheAverageDropsIt() throws Exception {
        String first = deliveredOrder();
        String second = deliveredOrder();
        String bad = JsonPath.read(call(customer, post("/api/orders/" + first + "/review"), Map.of("rating", 1, "comment", "Vi phạm")).andReturn().getResponse().getContentAsString(), "$.id");
        call(customer, post("/api/orders/" + second + "/review"), Map.of("rating", 5)).andExpect(status().isCreated());
        assertThat(menuRating()).isEqualTo(3.0);

        call(admin, post("/api/admin/reviews/" + bad + "/hide"), Map.of("reason", " ")).andExpect(status().isBadRequest());
        call(admin, post("/api/admin/reviews/" + bad + "/hide"), Map.of("reason", "Ngôn từ xúc phạm")).andExpect(status().isOk())
                .andExpect(jsonPath("$.hidden").value(true)).andExpect(jsonPath("$.hiddenReason").value("Ngôn từ xúc phạm"));
        call(admin, post("/api/admin/reviews/" + bad + "/hide"), Map.of("reason", "Lần hai")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_HIDDEN"));
        assertThat(menuRating()).isEqualTo(5.0);

        // Gone from the public list and the shop's list; the author still sees it, labelled, and cannot edit it away.
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.ratingCount").value(1));
        call(seller, get("/api/merchant/reviews"), null).andExpect(jsonPath("$.total").value(1));
        call(seller, post("/api/merchant/reviews/" + bad + "/response"), Map.of("text", "Trả lời")).andExpect(status().isNotFound());
        call(customer, get("/api/orders/" + first + "/review"), null).andExpect(jsonPath("$.hidden").value(true)).andExpect(jsonPath("$.hiddenReason").value("Ngôn từ xúc phạm"));
        call(customer, put("/api/orders/" + first + "/review"), Map.of("rating", 5)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_HIDDEN"));
        call(customer, delete("/api/orders/" + first + "/review"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_HIDDEN"));
        call(customer, get("/api/orders/" + first), null).andExpect(jsonPath("$.review.hidden").value(true));

        call(admin, get("/api/admin/reviews?hidden=true&vendorId=" + vendorId), null).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(bad));
        call(admin, get("/api/admin/reviews?maxRating=1&vendorId=" + vendorId), null).andExpect(jsonPath("$.total").value(1));
        call(admin, get("/api/admin/reviews?vendorId=" + vendorId), null).andExpect(jsonPath("$.total").value(2));

        call(admin, post("/api/admin/reviews/" + bad + "/unhide"), null).andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(false));
        call(admin, post("/api/admin/reviews/" + bad + "/unhide"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_HIDDEN"));
        assertThat(menuRating()).isEqualTo(3.0);
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(jsonPath("$.total").value(2));

        // Both actions are on record with who did them and why.
        List<String> log = jdbc.sql("select action || ':' || coalesce(reason, '') from review_moderation_log where target_id = :id::uuid order by created_at")
                .param("id", bad).query(String.class).list();
        assertThat(log).containsExactly("HIDE:Ngôn từ xúc phạm", "UNHIDE:");
        assertThat(jdbc.sql("select count(*) from review_moderation_log where target_id = :id::uuid and admin_id is not null").param("id", bad).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void anAdminHidesAReplyAndOnlyTheShopStillSeesIt() throws Exception {
        String id = deliveredOrder();
        String review = JsonPath.read(call(customer, post("/api/orders/" + id + "/review"), Map.of("rating", 3)).andReturn().getResponse().getContentAsString(), "$.id");
        call(seller, post("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Nội dung vi phạm")).andExpect(status().isCreated());
        String reply = JsonPath.read(call(admin, get("/api/admin/reviews?vendorId=" + vendorId), null).andReturn().getResponse().getContentAsString(), "$.items[0].reply.id");

        call(admin, post("/api/admin/review-responses/" + reply + "/hide"), Map.of("reason", "Công kích khách")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply.hidden").value(true));
        call(admin, post("/api/admin/review-responses/" + reply + "/hide"), Map.of("reason", "Lần hai")).andExpect(status().isConflict());
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(jsonPath("$.items[0].reply").doesNotExist());
        call(customer, get("/api/orders/" + id + "/review"), null).andExpect(jsonPath("$.reply").doesNotExist());
        call(seller, get("/api/merchant/reviews"), null).andExpect(jsonPath("$.items[0].reply.hidden").value(true))
                .andExpect(jsonPath("$.items[0].reply.hiddenReason").value("Công kích khách"));
        call(seller, put("/api/merchant/reviews/" + review + "/response"), Map.of("text", "Sửa lại")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSE_HIDDEN"));
        // Hiding a reply leaves the rating alone.
        assertThat(menuRating()).isEqualTo(3.0);

        call(admin, post("/api/admin/review-responses/" + reply + "/unhide"), null).andExpect(status().isOk()).andExpect(jsonPath("$.reply.hidden").value(false));
        call(null, get("/api/vendors/" + vendorId + "/reviews"), null).andExpect(jsonPath("$.items[0].reply.text").value("Nội dung vi phạm"));
        call(admin, post("/api/admin/review-responses/" + UUID.randomUUID() + "/hide"), Map.of("reason", "x")).andExpect(status().isNotFound());
    }

    @Test
    void moderationNeedsThePermission() throws Exception {
        call(seller, get("/api/admin/reviews"), null).andExpect(status().isForbidden());
        call(customer, post("/api/admin/reviews/" + UUID.randomUUID() + "/hide"), Map.of("reason", "x")).andExpect(status().isForbidden());
        call(null, get("/api/admin/reviews"), null).andExpect(status().isUnauthorized());
        call(admin, post("/api/admin/reviews/" + UUID.randomUUID() + "/hide"), Map.of("reason", "x")).andExpect(status().isNotFound());
    }

    // --- helpers

    private Double menuRating() throws Exception {
        String body = call(null, get("/api/vendors/" + vendorId + "/menu"), null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(body, "$.shop.ratingCount")).isEqualTo(
                jdbc.sql("select rating_count from vendors where id = :v").param("v", vendorId).query(Integer.class).single());
        Object average = JsonPath.read(body, "$.shop.ratingAverage");
        return average == null ? null : ((Number) average).doubleValue();
    }

    private String deliveredOrder() throws Exception {
        String id = place(customer);
        deliver(id);
        return id;
    }

    private void deliver(String orderId) {
        jdbc.sql("update orders set status = 'DELIVERED', finished_at = now() where id = :i::uuid").param("i", orderId).update();
    }

    private String place(String token) throws Exception {
        keySeq++;
        Map<String, Object> body = Map.of("vendorId", vendorId.toString(), "addressId", addressId, "paymentMethod", "COD",
                "items", List.of(Map.of("menuItemId", dishId, "quantity", 1)));
        return JsonPath.read(call(token, post("/api/orders"), body, "key-review-" + System.nanoTime() + keySeq).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
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
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        return mvc.perform(request);
    }
}
