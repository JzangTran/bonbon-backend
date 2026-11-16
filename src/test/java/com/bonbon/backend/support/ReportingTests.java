package com.bonbon.backend.support;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reporting and the other S12 moderation pieces (flows/reporting/, send-message.md "Blocking", configure-auto-reply.md,
 * chat-with-chatbot.md): blocking a conversation, the shop's auto-reply, the word list, filing and deciding reports, contesting, and the
 * help-center chatbot. Each test is one use case; the case codes match docs/test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReportingTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    UUID sellerId;
    String seller;
    UUID vendorId;
    UUID customerId;
    String customer;
    String admin;

    @BeforeEach
    void setUp() {
        sellerId = newUser("Chủ Quán", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        vendorId = UUID.randomUUID();
        jdbc.sql("""
                insert into vendors (id, owner_user_id, name, status, lat, lng, delivery_radius_km, delivery_fee, created_at, updated_at, decided_at)
                values (:id, :o, :n, 'APPROVED', 10.78, 106.7, 2, 10000, now(), now(), now())""")
                .param("id", vendorId).param("o", sellerId).param("n", "Quán Báo Cáo " + System.nanoTime()).update();
        customerId = newUser("Khách Hàng", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
        admin = tokenOf(newUser("Quản Trị", Role.ADMIN), Role.ADMIN);
    }

    // ---------------------------------------------------------------- UC-016b auto-reply

    @Test
    void autoReplyIsSentOncePerUnansweredStreak() throws Exception {
        call(seller, get("/api/merchant/shop/auto-reply"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.message").value("Cảm ơn bạn đã nhắn tin, quán sẽ phản hồi sớm nhất có thể!"));
        String conversation = firstMessage("Quán mở chưa?");
        sendIn(customer, conversation, "Cho mình hỏi thêm").andExpect(status().isCreated());
        // TL-01: two customer messages, one auto-reply
        assertThat(autoReplies(conversation)).isEqualTo(1);
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.autoReply == true)].sender").value(org.hamcrest.Matchers.contains("SHOP")));
        // TL-02: the shop answers, the streak ends; the next customer message is answered automatically again
        sendIn(seller, conversation, "Quán mở rồi nhé").andExpect(status().isCreated());
        sendIn(customer, conversation, "Vậy cho mình 1 phần").andExpect(status().isCreated());
        assertThat(autoReplies(conversation)).isEqualTo(2);
        // TL-03: turned off, nothing more is sent
        call(seller, put("/api/merchant/shop/auto-reply"), Map.of("enabled", false)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        sendIn(seller, conversation, "Có ngay").andExpect(status().isCreated());
        sendIn(customer, conversation, "Cảm ơn").andExpect(status().isCreated());
        assertThat(autoReplies(conversation)).isEqualTo(2);
    }

    @Test
    void autoReplyMessageIsEditedAndCannotBeBlank() throws Exception {
        call(seller, put("/api/merchant/shop/auto-reply"), Map.of("message", "Quán đang bận, 5 phút nữa trả lời bạn nhé!")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Quán đang bận, 5 phút nữa trả lời bạn nhé!"));
        call(seller, put("/api/merchant/shop/auto-reply"), Map.of("message", "   ")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTO_REPLY_EMPTY"));
        call(seller, put("/api/merchant/shop/auto-reply"), Map.of("message", "x".repeat(1001))).andExpect(status().isBadRequest());
        String conversation = firstMessage("Alo");
        call(customer, get("/api/conversations/" + conversation + "/messages"), null)
                .andExpect(jsonPath("$.items[0].text").value("Quán đang bận, 5 phút nữa trả lời bạn nhé!"))
                .andExpect(jsonPath("$.items[0].autoReply").value(true));
    }

    // ---------------------------------------------------------------- UC-013b blocking

    @Test
    void aBlockedSideCannotSendAndUnblockingIsImmediate() throws Exception {
        String conversation = firstMessage("Chào quán");
        call(customer, post("/api/conversations/" + conversation + "/block"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedByMe").value(true)).andExpect(jsonPath("$.blockedByOther").value(false));
        // CT-01: the shop cannot write, and is not told more than that
        sendIn(seller, conversation, "Bạn ơi").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_BLOCKED"));
        // CT-02: the customer who blocked cannot either, until unblocking
        sendIn(customer, conversation, "Thôi").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONVERSATION_BLOCKED_BY_YOU"));
        call(seller, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].blockedByOther").value(true));
        // CT-03: history stays readable to both
        call(seller, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isOk());
        // CT-04: unblocking works at once
        call(customer, delete("/api/conversations/" + conversation + "/block"), null).andExpect(status().isOk()).andExpect(jsonPath("$.blockedByMe").value(false));
        sendIn(seller, conversation, "Bạn ơi").andExpect(status().isCreated());
        // CT-05: someone else's conversation is not found
        String stranger = tokenOf(newUser("Người Lạ", Role.CUSTOMER), Role.CUSTOMER);
        call(stranger, post("/api/conversations/" + conversation + "/block"), null).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- word list

    @Test
    void listedWordsAreMaskedForTheReaderWithOrWithoutAccents() throws Exception {
        String term = "lừa đảo" + (System.nanoTime() % 1000);
        call(admin, post("/api/admin/moderation-terms"), Map.of("term", term)).andExpect(status().isCreated());
        call(admin, post("/api/admin/moderation-terms"), Map.of("term", term.toUpperCase())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERM_EXISTS"));
        call(seller, post("/api/admin/moderation-terms"), Map.of("term", "abc")).andExpect(status().isForbidden());
        String conversation = firstMessage("Chào quán");
        String plain = term.replace("ừ", "u").replace("đ", "d").replace("ả", "a");
        sendIn(seller, conversation, "Đây là " + plain + " đó").andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Đây là " + plain + " đó"));
        String masked = "Đây là " + "*".repeat(plain.indexOf(' ')) + " " + "*".repeat(plain.length() - plain.indexOf(' ') - 1) + " đó";
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(jsonPath("$.items[0].text").value(masked));
    }

    // ---------------------------------------------------------------- UC-029b reporting

    @Test
    void aReportedMessageIsRemovedAndItsSenderRestrictedThenAContestOverturnsIt() throws Exception {
        String conversation = firstMessage("Chào quán");
        String messageId = JsonPath.read(sendIn(seller, conversation, "Chuyển khoản riêng cho tôi nhé").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // BC-01: the customer reports the shop's message; the evidence is copied and the message still shows
        String reportId = JsonPath.read(report(customer, "MESSAGE", messageId, "SCAM_OR_FRAUD", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyReported").value(false)).andReturn().getResponse().getContentAsString(), "$.id");
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(jsonPath("$.items[0].removed").value(false));
        // BC-02: the same report again returns the first one
        report(customer, "MESSAGE", messageId, "SPAM", null).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(reportId))
                .andExpect(jsonPath("$.alreadyReported").value(true));
        // BC-03: the shop cannot report its own message
        report(seller, "MESSAGE", messageId, "SPAM", null).andExpect(status().isNotFound());
        // BC-04: the queue shows it, with the evidence and the actions this administrator may take
        call(admin, get("/api/admin/reports"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id == '" + reportId + "')].targetType")
                .value(org.hamcrest.Matchers.contains("MESSAGE")));
        call(admin, get("/api/admin/reports/" + reportId), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.message.text").value("Chuyển khoản riêng cho tôi nhé"))
                .andExpect(jsonPath("$.evidence.before[0].text").value("Chào quán"))
                .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.hasItems("DISMISS", "HIDE", "RESTRICT", "ESCALATE")));
        // BC-05: restricting needs days
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "RESTRICT", "reason", "Đòi trả tiền ngoài ứng dụng"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESTRICT_DAYS_REQUIRED"));
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "RESTRICT", "reason", "Đòi trả tiền ngoài ứng dụng", "restrictDays", 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIONED")).andExpect(jsonPath("$.decisionCode").value("MESSAGING_RESTRICTED"));
        // BC-06: the message is removed for both sides, the shop cannot write, both people are told
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(jsonPath("$.items[0].removed").value(true))
                .andExpect(jsonPath("$.items[0].text").doesNotExist());
        sendIn(seller, conversation, "Sao vậy").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MESSAGING_RESTRICTED"));
        assertThat(notifications(customerId, "REPORT_DECIDED")).isEqualTo(1);
        assertThat(notifications(sellerId, "REPORT_ACTIONED")).isEqualTo(1);
        // BC-07: deciding twice is refused
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "DISMISS", "reason", "x")).andExpect(status().isConflict());
        // BC-08: only the affected person contests, once
        call(customer, post("/api/reports/" + reportId + "/contest"), Map.of("note", "Tôi muốn xem lại")).andExpect(status().isNotFound());
        call(seller, post("/api/reports/" + reportId + "/contest"), Map.of("note", "Tôi chỉ đưa số tài khoản của quán")).andExpect(status().isNoContent());
        call(seller, post("/api/reports/" + reportId + "/contest"), Map.of("note", "Lần nữa")).andExpect(status().isConflict());
        // BC-09: a second look finding no violation restores the message and lifts the restriction
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "DISMISS", "reason", "Số tài khoản của chính quán, được phép"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(jsonPath("$.items[0].removed").value(false));
        sendIn(seller, conversation, "Cảm ơn bạn").andExpect(status().isCreated());
    }

    @Test
    void reportingRulesForShopsReasonsAndRoles() throws Exception {
        // BC-10: a customer reports a shop; OTHER needs a note
        report(customer, "SHOP", vendorId.toString(), "OTHER", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NOTE_REQUIRED"));
        report(customer, "SHOP", vendorId.toString(), "OTHER", "Ảnh món không đúng thực tế").andExpect(status().isCreated());
        // BC-11: a seller cannot report a shop here, nor their own shop
        report(seller, "SHOP", vendorId.toString(), "SPAM", null).andExpect(status().isForbidden());
        // BC-12: an unknown target is not found, an unknown reason is refused
        report(customer, "SHOP", UUID.randomUUID().toString(), "SPAM", null).andExpect(status().isNotFound());
        report(customer, "SHOP", vendorId.toString(), "RUDE", null).andExpect(status().isBadRequest());
        // BC-13: an administrator's escalation closes it as actioned without hiding anything
        String reportId = JsonPath.read(call(admin, get("/api/admin/reports?targetType=SHOP"), null).andReturn().getResponse().getContentAsString(),
                "$.items[0].id");
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "HIDE", "reason", "x")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_ALLOWED"));
        call(admin, post("/api/admin/reports/" + reportId + "/decide"), Map.of("action", "ESCALATE", "reason", "Chuyển xem xét đình chỉ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decisionCode").value("ESCALATED"));
        // BC-14: a customer and a seller only reach the queue through their own reports
        call(customer, get("/api/admin/reports"), null).andExpect(status().isForbidden());
    }

    @Test
    void reportsArePerDayLimited() throws Exception {
        String conversation = firstMessage("Chào");
        for (int i = 0; i < 10; i++) {
            String m = JsonPath.read(sendIn(seller, conversation, "Tin " + i).andReturn().getResponse().getContentAsString(), "$.id");
            report(customer, "MESSAGE", m, "SPAM", null).andExpect(status().isCreated());
        }
        String eleventh = JsonPath.read(sendIn(seller, conversation, "Tin 11").andReturn().getResponse().getContentAsString(), "$.id");
        // BC-15
        report(customer, "MESSAGE", eleventh, "SPAM", null).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_REPORTS"));
    }

    // ---------------------------------------------------------------- UC-030b help-center chatbot

    @Test
    void theChatbotAnswersFromAPublishedArticleAndLogsWhatItCannot() throws Exception {
        // a keyword no seeded article has, so only this article can match it
        String tag = "mãhoàn" + System.nanoTime();
        call(admin, post("/api/admin/help-articles"), Map.of("title", "Khi nào tôi được hoàn tiền?", "body", "Đơn trả trước bị huỷ được hoàn trong 1–3 ngày.",
                "audience", "CUSTOMER", "keywords", java.util.List.of(tag), "status", "PUBLISHED")).andExpect(status().isCreated());
        // HD-01: a guest asks without accents; the keyword still matches
        String folded = tag.replace("ã", "a").replace("à", "a");
        mvc.perform(post("/api/help-articles/ask").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("question", "Lam sao voi " + folded.toUpperCase() + " vay?"))).with(r -> { r.setRemoteAddr("10.9.8.7"); return r; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.answer").value("Đơn trả trước bị huỷ được hoàn trong 1–3 ngày."));
        // HD-02: nothing matches; the answer points to the help center and the question is kept for the administrator
        String nothing = "Quán có bán kèm đá viên " + System.nanoTime() + " không";
        call(customer, post("/api/help-articles/ask"), Map.of("question", nothing)).andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(false));
        call(admin, get("/api/admin/help-articles/unanswered-questions"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.question == '" + nothing + "')].times").value(org.hamcrest.Matchers.contains(1)));
        // HD-03: a seller does not get a customer-only article
        call(seller, post("/api/help-articles/ask"), Map.of("question", "Lam sao voi " + folded)).andExpect(jsonPath("$.matched").value(false));
        // HD-04: too short a question is refused
        call(customer, post("/api/help-articles/ask"), Map.of("question", "a")).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- helpers

    private long autoReplies(String conversation) {
        return jdbc.sql("select count(*) from communication.messages where conversation_id = cast(:c as uuid) and is_auto_reply").param("c", conversation)
                .query(Long.class).single();
    }

    private long notifications(UUID userId, String type) {
        return jdbc.sql("select count(*) from notifications where recipient_id = :u and type = :t").param("u", userId).param("t", type).query(Long.class).single();
    }

    private ResultActions report(String token, String type, String id, String reason, String note) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("targetType", type, "targetId", id, "reasonCode", reason));
        if (note != null) {
            body.put("note", note);
        }
        return call(token, post("/api/reports"), body);
    }

    private String firstMessage(String text) throws Exception {
        return JsonPath.read(call(customer, post("/api/shops/" + vendorId + "/messages"), Map.of("text", text)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.conversationId");
    }

    private ResultActions sendIn(String token, String conversation, String text) throws Exception {
        return call(token, post("/api/conversations/" + conversation + "/messages"), Map.of("text", text));
    }

    private UUID newUser(String name, Role role) {
        User u = new User(name.replace(' ', '-').toLowerCase() + "-" + System.nanoTime() + "@example.com", null, name);
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
