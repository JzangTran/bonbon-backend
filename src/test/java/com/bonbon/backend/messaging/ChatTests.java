package com.bonbon.backend.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.authentication.Role;
import com.bonbon.backend.authentication.entity.User;
import com.bonbon.backend.authentication.repository.UserRepository;
import com.bonbon.backend.authentication.service.TokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat between a customer and a shop (flows/messaging/). The shop is approved directly in the database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "bonbon.realtime.auth-timeout=PT2S")
class ChatTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository users;

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcClient jdbc;

    String seller;
    UUID vendorId;
    String customer;
    UUID customerId;
    final List<WebSocketSession> open = new ArrayList<>();

    static class Probe extends TextWebSocketHandler {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(JSON.readTree(message.getPayload()));
        }

        JsonNode next(long seconds) throws InterruptedException {
            return messages.poll(seconds, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void setUp() {
        UUID sellerId = newUser("Chủ quán", Role.SELLER);
        seller = tokenOf(sellerId, Role.SELLER);
        vendorId = newShop(sellerId, "Quán Trò Chuyện " + System.nanoTime(), "APPROVED");
        customerId = newUser("Khách Hàng", Role.CUSTOMER);
        customer = tokenOf(customerId, Role.CUSTOMER);
    }

    @AfterEach
    void closeSockets() throws Exception {
        for (WebSocketSession s : open) {
            if (s.isOpen()) {
                s.close();
            }
        }
    }

    @Test
    void theFirstMessageCreatesTheConversationAndTheShopAnswersInIt() throws Exception {
        call(customer, get("/api/shops/" + vendorId + "/conversation"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        String conversation = JsonPath.read(send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "  Quán còn cơm không?  "))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.text").value("Quán còn cơm không?")).andExpect(jsonPath("$.sender").value("CUSTOMER"))
                .andExpect(jsonPath("$.mine").value(true)).andReturn().getResponse().getContentAsString(), "$.conversationId");

        // A second message from the same customer reuses the conversation.
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "Cho mình 2 phần")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversationId").value(conversation));
        assertThat(jdbc.sql("select count(*) from conversations where customer_id = :c").param("c", customerId).query(Long.class).single()).isEqualTo(1);

        call(customer, get("/api/shops/" + vendorId + "/conversation"), null).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(conversation))
                .andExpect(jsonPath("$.lastMessage.text").value("Cho mình 2 phần"));

        // The shop sees the customer's name, and its answer is the shop's.
        call(seller, get("/api/conversations"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(conversation))
                .andExpect(jsonPath("$.items[0].customerName").value("Khách Hàng")).andExpect(jsonPath("$.items[0].unread").value(true))
                .andExpect(jsonPath("$.unreadConversations").value(1));
        send(seller, "/api/conversations/" + conversation + "/messages", Map.of("text", "Còn nhé bạn")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender").value("SHOP")).andExpect(jsonPath("$.mine").value(true));
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].text").value("Còn nhé bạn")).andExpect(jsonPath("$.items[0].mine").value(false))
                .andExpect(jsonPath("$.items[0].sender").value("SHOP"));
    }

    @Test
    void unreadIsTrackedPerSide() throws Exception {
        String conversation = firstMessage("Xin chào");
        call(seller, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].unread").value(true));
        // The sender's own side is already read.
        call(customer, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].unread").value(false)).andExpect(jsonPath("$.unreadConversations").value(0));

        call(seller, post("/api/conversations/" + conversation + "/read"), null).andExpect(status().isNoContent());
        call(seller, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].unread").value(false)).andExpect(jsonPath("$.unreadConversations").value(0));

        Thread.sleep(20);
        send(seller, "/api/conversations/" + conversation + "/messages", Map.of("text", "Chào bạn")).andExpect(status().isCreated());
        call(customer, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].unread").value(true)).andExpect(jsonPath("$.unreadConversations").value(1));
        call(customer, post("/api/conversations/" + conversation + "/read"), null).andExpect(status().isNoContent());
        call(customer, get("/api/conversations"), null).andExpect(jsonPath("$.items[0].unread").value(false));
    }

    @Test
    void historyIsPagedNewestFirst() throws Exception {
        String conversation = firstMessage("tin 1");
        for (int i = 2; i <= 5; i++) {
            Thread.sleep(5);
            send(customer, "/api/conversations/" + conversation + "/messages", Map.of("text", "tin " + i)).andExpect(status().isCreated());
        }
        String page = call(customer, get("/api/conversations/" + conversation + "/messages?size=2"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].text").value("tin 5")).andExpect(jsonPath("$.hasMore").value(true))
                .andReturn().getResponse().getContentAsString();
        String before = JsonPath.read(page, "$.nextBefore");
        call(customer, get("/api/conversations/" + conversation + "/messages?size=10&before=" + before), null).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].text").value("tin 3")).andExpect(jsonPath("$.hasMore").value(false)).andExpect(jsonPath("$.nextBefore").doesNotExist());
        call(customer, get("/api/conversations/" + conversation + "/messages?size=0"), null).andExpect(status().isBadRequest());
    }

    @Test
    void aMessageNeedsTextOrAnImageAndRespectsTheLimit() throws Exception {
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "   ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MESSAGE_EMPTY"));
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "a".repeat(1001))).andExpect(status().isBadRequest());
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "a".repeat(1000))).andExpect(status().isCreated());
        // Emoji are plain text.
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", "Cảm ơn quán 😀🍜")).andExpect(status().isCreated()).andExpect(jsonPath("$.text").value("Cảm ơn quán 😀🍜"));
    }

    @Test
    void anImageGoesThroughUploadFirstAndIsServedByASignedLink() throws Exception {
        mvc.perform(multipart("/api/conversations/images").file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isUnsupportedMediaType());
        String key = JsonPath.read(mvc.perform(multipart("/api/conversations/images").file(new MockMultipartFile("file", "a.png", "image/png", PNG))
                .header("Authorization", "Bearer " + customer)).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").exists()).andReturn().getResponse()
                .getContentAsString(), "$.imageKey");
        assertThat(key).startsWith("chat/" + customerId + "/");

        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("imageKey", key)).andExpect(status().isCreated()).andExpect(jsonPath("$.imageUrl").exists())
                .andExpect(jsonPath("$.text").doesNotExist());
        // Somebody else's key, or a made-up one, is refused.
        send(customer, "/api/shops/" + vendorId + "/messages", Map.of("imageKey", "chat/" + UUID.randomUUID() + "/x.png")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_INVALID"));
    }

    @Test
    void aReplyMustPointAtAMessageOfTheSameConversation() throws Exception {
        String conversation = firstMessage("Có cơm gà không?");
        String first = JsonPath.read(call(customer, get("/api/conversations/" + conversation + "/messages"), null).andReturn().getResponse().getContentAsString(), "$.items[0].id");
        send(seller, "/api/conversations/" + conversation + "/messages", Map.of("text", "Có nhé", "replyToMessageId", first)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.replyTo.id").value(first)).andExpect(jsonPath("$.replyTo.text").value("Có cơm gà không?")).andExpect(jsonPath("$.replyTo.sender").value("CUSTOMER"));

        UUID otherCustomer = newUser("Khách Khác", Role.CUSTOMER);
        String other = tokenOf(otherCustomer, Role.CUSTOMER);
        String otherConversation = JsonPath.read(send(other, "/api/shops/" + vendorId + "/messages", Map.of("text", "Chào")).andReturn().getResponse().getContentAsString(), "$.conversationId");
        send(other, "/api/conversations/" + otherConversation + "/messages", Map.of("text", "?", "replyToMessageId", first)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REPLY_NOT_FOUND"));
    }

    @Test
    void eachSideOnlySeesItsOwnConversations() throws Exception {
        String conversation = firstMessage("Riêng tư");
        String stranger = tokenOf(newUser("Người lạ", Role.CUSTOMER), Role.CUSTOMER);
        call(stranger, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isNotFound());
        send(stranger, "/api/conversations/" + conversation + "/messages", Map.of("text", "xen vào")).andExpect(status().isNotFound());
        call(stranger, post("/api/conversations/" + conversation + "/read"), null).andExpect(status().isNotFound());
        call(stranger, get("/api/conversations"), null).andExpect(jsonPath("$.items.length()").value(0));

        UUID otherSellerId = newUser("Chủ quán khác", Role.SELLER);
        String otherSeller = tokenOf(otherSellerId, Role.SELLER);
        newShop(otherSellerId, "Quán Khác " + System.nanoTime(), "APPROVED");
        call(otherSeller, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isNotFound());
        send(otherSeller, "/api/conversations/" + conversation + "/messages", Map.of("text", "xen vào")).andExpect(status().isNotFound());

        // The shop does not start conversations, and an admin takes no part.
        send(seller, "/api/shops/" + vendorId + "/messages", Map.of("text", "xin chào")).andExpect(status().isForbidden());
        String admin = tokenOf(newUser("Admin", Role.ADMIN), Role.ADMIN);
        call(admin, get("/api/conversations"), null).andExpect(status().isForbidden());
    }

    @Test
    void aSellerWithoutAnApprovedShopHasNoConversations() throws Exception {
        UUID sellerId = newUser("Chủ mới", Role.SELLER);
        call(tokenOf(sellerId, Role.SELLER), get("/api/conversations"), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHOP_NOT_APPROVED"));
    }

    @Test
    void aCustomerCannotWriteToAShopThatTakesNoOrders() throws Exception {
        send(customer, "/api/shops/" + UUID.randomUUID() + "/messages", Map.of("text", "?")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_FOUND"));
        UUID pendingOwner = newUser("Chủ chờ duyệt", Role.SELLER);
        UUID pending = newShop(pendingOwner, "Quán Chờ " + System.nanoTime(), "PENDING");
        send(customer, "/api/shops/" + pending + "/messages", Map.of("text", "?")).andExpect(status().isNotFound());

        String conversation = firstMessage("Trước khi đình chỉ");
        jdbc.sql("update vendors set status = 'SUSPENDED' where id = :id").param("id", vendorId).update();
        send(customer, "/api/conversations/" + conversation + "/messages", Map.of("text", "Còn đó không?")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHOP_UNAVAILABLE"));
        // History stays readable and the shop can still answer.
        call(customer, get("/api/conversations/" + conversation + "/messages"), null).andExpect(status().isOk());
        send(seller, "/api/conversations/" + conversation + "/messages", Map.of("text", "Quán tạm nghỉ nhé")).andExpect(status().isCreated());
    }

    @Test
    void theOtherSideIsToldOverTheSocket() throws Exception {
        Probe shopProbe = connect(seller);
        Probe customerProbe = connect(customer);
        assertThat(shopProbe.next(5).path("type").asString()).isEqualTo("ready");
        assertThat(customerProbe.next(5).path("type").asString()).isEqualTo("ready");

        String conversation = firstMessage("Có ai không?");
        JsonNode heard = shopProbe.next(5);
        assertThat(heard.path("type").asString()).isEqualTo("message");
        assertThat(heard.path("channel").asString()).isEqualTo("shop");
        assertThat(heard.path("conversationId").asString()).isEqualTo(conversation);
        // The sender already has the message from the request.
        assertThat(customerProbe.next(1)).isNull();

        send(seller, "/api/conversations/" + conversation + "/messages", Map.of("text", "Có đây")).andExpect(status().isCreated());
        JsonNode answer = customerProbe.next(5);
        assertThat(answer.path("type").asString()).isEqualTo("message");
        assertThat(answer.path("channel").asString()).isEqualTo("customer");
    }

    // --- helpers

    private String firstMessage(String text) throws Exception {
        return JsonPath.read(send(customer, "/api/shops/" + vendorId + "/messages", Map.of("text", text)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString(), "$.conversationId");
    }

    private ResultActions send(String token, String path, Map<String, Object> body) throws Exception {
        return call(token, post(path), body);
    }

    private Probe connect(String token) throws Exception {
        Probe probe = new Probe();
        WebSocketSession session = new StandardWebSocketClient().execute(probe, "ws://localhost:" + port + "/ws/orders").get(5, TimeUnit.SECONDS);
        open.add(session);
        session.sendMessage(new TextMessage("{\"type\":\"auth\",\"token\":\"" + token + "\"}"));
        return probe;
    }

    private UUID newShop(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into vendors (id, owner_user_id, name, status, lat, lng, delivery_radius_km, delivery_fee, created_at, updated_at, decided_at)
                values (:id, :o, :n, :s, 10.78, 106.7, 2, 10000, now(), now(), now())""")
                .param("id", id).param("o", ownerId).param("n", name).param("s", status).update();
        return id;
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
