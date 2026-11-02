package com.bonbon.backend.identity.authentication.controller;

import com.bonbon.backend.identity.authentication.IssuedSession;
import com.bonbon.backend.identity.authentication.dto.LoginResponse;
import com.bonbon.backend.identity.authentication.dto.RegisterResponse;
import com.bonbon.backend.identity.authentication.dto.TokenResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * For the web app, every response that hands out a refresh token (sign-in, sign-up with a session, role switch, refresh, password
 * change, admin sign-in) moves that token into the HttpOnly cookie and drops it from the JSON. One place, so no endpoint can forget.
 */
@RestControllerAdvice
class RefreshCookieAdvice implements ResponseBodyAdvice<Object> {

    private final RefreshCookies cookies;

    RefreshCookieAdvice(RefreshCookies cookies) {
        this.cookies = cookies;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (!(request instanceof ServletServerHttpRequest req) || !(response instanceof ServletServerHttpResponse res)) return body;
        if (!RefreshCookies.isWeb(req.getServletRequest())) return body;
        return switch (body) {
            case TokenResponse t when t.refreshToken() != null -> {
                cookies.write(res.getServletResponse(), t.refreshToken());
                yield t.withoutRefreshToken();
            }
            case LoginResponse l when l.tokens() != null && l.tokens().refreshToken() != null -> {
                cookies.write(res.getServletResponse(), l.tokens().refreshToken());
                yield new LoginResponse(l.needsRoleSelection(), l.availableRoles(), l.roleToken(), l.tokens().withoutRefreshToken(), l.user(),
                        l.deletionCancelled());
            }
            case RegisterResponse r when r.tokens() != null && r.tokens().refreshToken() != null -> {
                cookies.write(res.getServletResponse(), r.tokens().refreshToken());
                yield new RegisterResponse(r.status(), r.tokens().withoutRefreshToken());
            }
            case IssuedSession s when s.refreshToken() != null -> {
                cookies.write(res.getServletResponse(), s.refreshToken());
                yield new IssuedSession(s.accessToken(), null, s.expiresIn(), s.userId(), s.email(), s.name(), s.role());
            }
            case null, default -> body;
        };
    }
}
