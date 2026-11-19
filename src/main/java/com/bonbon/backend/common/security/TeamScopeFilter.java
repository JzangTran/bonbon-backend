package com.bonbon.backend.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A main account or member works a shop on the owner's behalf: its token names the owner as subject so the shop is found, which must
 * not open the owner's personal endpoints (profile, password, addresses, data export, notifications). Team sessions
 * may only call the seller side, the team console and the public reference data; everything else is 403.
 */
public class TeamScopeFilter extends OncePerRequestFilter {

    static final List<String> ALLOWED = List.of(
            "/api/merchant/", "/api/seller-team/", "/api/conversations", "/api/geo/", "/api/public/", "/api/categories",
            "/api/display-categories", "/api/help-articles", "/api/legal/documents", "/api/app/config", "/api/auth/logout", "/api/auth/refresh");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getClaimAsString(CurrentPrincipal.CLAIM_TEAM) != null) {
            String path = request.getRequestURI();
            if (ALLOWED.stream().noneMatch(path::startsWith)) {
                SecurityConfig.writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "TEAM_SESSION_NOT_ALLOWED",
                        "Tài khoản chính và thành viên chỉ dùng được các trang của cửa hàng.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
