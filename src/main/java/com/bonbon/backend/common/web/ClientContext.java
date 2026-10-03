package com.bonbon.backend.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is calling, as evidence for consent records and as the key for IP rate limits. Clients send
 * {@code X-Client-Channel} (WEB or MOBILE) and {@code X-App-Version}; the IP is the connection's address
 * (behind the reverse proxy, set server.forward-headers-strategy so it is the real client).
 */
public record ClientContext(String channel, String appVersion, String ip) {

    public static ClientContext from(HttpServletRequest request) {
        String channel = request.getHeader("X-Client-Channel");
        String normalized = "MOBILE".equalsIgnoreCase(channel) ? "MOBILE" : "WEB";
        return new ClientContext(normalized, request.getHeader("X-App-Version"), request.getRemoteAddr());
    }
}
