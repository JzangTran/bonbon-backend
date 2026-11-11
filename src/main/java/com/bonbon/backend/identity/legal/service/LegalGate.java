package com.bonbon.backend.identity.legal.service;

import java.util.List;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.legal.dto.LegalAdminViews;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The acceptance gate: while a customer or seller has a changed document to accept, every call answers 403
 * {@code LEGAL_ACCEPTANCE_REQUIRED} with the documents, except the calls that let them accept it, sign in or out, or read it.
 */
@Configuration
class LegalGate implements WebMvcConfigurer {

    private final LegalAcceptanceService acceptance;

    LegalGate(LegalAcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
                    return true;
                }
                CurrentPrincipal principal = CurrentPrincipal.from(jwt);
                if (!"CUSTOMER".equals(principal.activeRole()) && !"SELLER".equals(principal.activeRole())) {
                    return true;
                }
                List<LegalAdminViews.Pending> pending = acceptance.pendingCached(principal.id(), principal.activeRole());
                if (!pending.isEmpty()) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "LEGAL_ACCEPTANCE_REQUIRED",
                            "Điều khoản đã được cập nhật. Vui lòng xem và đồng ý để tiếp tục dùng Bonbon.").withProperty("documents", pending);
                }
                return true;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/legal/**", "/api/auth/**", "/api/admin/**", "/api/public/**", "/api/payments/**");
    }
}
