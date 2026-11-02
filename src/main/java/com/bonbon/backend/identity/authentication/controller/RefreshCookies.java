package com.bonbon.backend.identity.authentication.controller;

import com.bonbon.backend.common.security.JwtProperties;
import com.bonbon.backend.common.security.RefreshCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The web app's refresh token lives in an HttpOnly cookie instead of the response body, so a script injected into the page cannot
 * steal a long-lived credential; the access token stays short-lived and in memory. The phone app keeps the token in the body (it
 * stores it in the device's secure store), so this only applies to requests marked {@code X-Client-Channel: WEB}.
 */
@Component
public class RefreshCookies {

    private static final String PATH = "/api";

    private final RefreshCookieProperties props;
    private final Duration lifetime;

    RefreshCookies(RefreshCookieProperties props, JwtProperties jwt) {
        this.props = props;
        this.lifetime = jwt.refreshTtl();
    }

    public static boolean isWeb(HttpServletRequest request) {
        return "WEB".equalsIgnoreCase(request.getHeader("X-Client-Channel"));
    }

    /** The refresh token the browser sent, or null. */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (props.name().equals(c.getName()) && !c.getValue().isBlank()) return c.getValue();
        }
        return null;
    }

    /** The body's token when there is one (the phone app), else the cookie's (the web app). */
    public String pick(String fromBody, HttpServletRequest request) {
        return fromBody != null && !fromBody.isBlank() ? fromBody : read(request);
    }

    public void write(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, lifetime).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(props.name(), value)
                .httpOnly(true)
                .secure(props.secure())
                .sameSite(props.sameSite())
                .path(PATH)
                .maxAge(maxAge);
        if (!props.domain().isBlank()) b.domain(props.domain());
        return b.build();
    }
}
