package com.bonbon.backend.order.realtime;

import com.bonbon.backend.common.security.CorsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Opens {@code /ws/orders}. Browsers may connect from the same origins that may call the API; native apps send no origin. */
@Configuration
@EnableWebSocket
class OrderSocketConfig implements WebSocketConfigurer {

    private final OrderSocketHandler handler;
    private final CorsProperties cors;

    OrderSocketConfig(OrderSocketHandler handler, CorsProperties cors) {
        this.handler = handler;
        this.cors = cors;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/orders").setAllowedOrigins(cors.allowedOrigins().toArray(String[]::new));
    }
}
