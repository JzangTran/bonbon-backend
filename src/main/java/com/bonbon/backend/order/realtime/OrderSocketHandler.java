package com.bonbon.backend.order.realtime;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live order updates (track-order.md, view-new-orders-list.md). The handshake carries no credentials: the client's
 * first message must be {@code {"type":"auth","token":"<access token>"}}, otherwise the socket is closed after a few
 * seconds. A customer then listens to their own orders, a shop to its own orders, and nothing else; the push is only a
 * hint, so a client re-fetches the order and a missed message is harmless.
 */
@Component
class OrderSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderSocketHandler.class);
    private static final String AUTHED = "bonbon.authed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JwtDecoder jwtDecoder;
    private final ShopOrdering shops;
    private final OrderSocketRegistry registry;
    private final Duration authTimeout;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "order-socket-auth");
        t.setDaemon(true);
        return t;
    });

    OrderSocketHandler(JwtDecoder jwtDecoder, ShopOrdering shops, OrderSocketRegistry registry,
            @Value("${bonbon.realtime.auth-timeout:PT10S}") Duration authTimeout) {
        this.jwtDecoder = jwtDecoder;
        this.shops = shops;
        this.registry = registry;
        this.authTimeout = authTimeout;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 64 * 1024);
        raw.getAttributes().put("bonbon.session", session);
        timers.schedule(() -> {
            if (raw.isOpen() && !raw.getAttributes().containsKey(AUTHED)) {
                close(session, new CloseStatus(CloseStatus.POLICY_VIOLATION.getCode(), "auth timeout"));
            }
        }, authTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) {
        WebSocketSession session = (WebSocketSession) raw.getAttributes().getOrDefault("bonbon.session", raw);
        JsonNode body;
        try {
            body = JSON.readTree(message.getPayload());
        } catch (RuntimeException e) {
            send(session, Map.of("type", "error", "code", "BAD_MESSAGE"));
            return;
        }
        String type = body.path("type").asString("");
        if ("ping".equals(type)) {
            send(session, Map.of("type", "pong"));
        } else if ("auth".equals(type)) {
            authenticate(raw, session, body.path("token").asString(""));
        } else {
            send(session, Map.of("type", "error", "code", "BAD_MESSAGE"));
        }
    }

    private void authenticate(WebSocketSession raw, WebSocketSession session, String token) {
        if (raw.getAttributes().containsKey(AUTHED)) {
            return;
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token);
        } catch (JwtException e) {
            send(session, Map.of("type", "error", "code", "UNAUTHENTICATED"));
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        CurrentPrincipal principal = CurrentPrincipal.from(jwt);
        Instant validUntil = jwt.getExpiresAt() == null ? Instant.now().plus(Duration.ofMinutes(15)) : jwt.getExpiresAt();
        List<String> channels = new ArrayList<>();
        if (principal.can("order:create")) {
            registry.subscribe(customerChannel(principal.id()), new OrderSocketRegistry.Subscription(session, validUntil));
            channels.add("customer");
        }
        if (principal.can("order:read")) {
            shops.approvedVendorOwnedBy(principal.id()).ifPresent(vendorId -> {
                registry.subscribe(vendorChannel(vendorId), new OrderSocketRegistry.Subscription(session, validUntil));
                channels.add("shop");
            });
        }
        if (channels.isEmpty()) {
            send(session, Map.of("type", "error", "code", "FORBIDDEN"));
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        raw.getAttributes().put(AUTHED, true);
        send(session, Map.of("type", "ready", "channels", channels));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        registry.remove(raw);
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        registry.remove(raw);
    }

    /** Sends a JSON event to a socket; a failure closes that socket and never reaches the caller. */
    static void send(WebSocketSession session, Object payload) {
        try {
            session.sendMessage(new TextMessage(JSON.writeValueAsString(payload)));
        } catch (IOException | RuntimeException e) {
            log.debug("Dropping socket {}: {}", session.getId(), e.getMessage());
            close(session, CloseStatus.SERVER_ERROR);
        }
    }

    static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not close socket {}", session.getId());
        }
    }

    /** Used by tests and the broadcaster to name a user's channel. */
    static String customerChannel(UUID customerId) {
        return "customer:" + customerId;
    }

    static String vendorChannel(UUID vendorId) {
        return "vendor:" + vendorId;
    }
}
