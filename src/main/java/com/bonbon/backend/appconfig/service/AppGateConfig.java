package com.bonbon.backend.appconfig.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import com.bonbon.backend.appconfig.dto.AppConfigViews;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The one filter in front of the API that enforces the app policy (app-operations.md), before authentication so that even a
 * request with a bad token learns the app is too old or the system is down. The calls that let an app find out (the public
 * config), sign in, and the payment providers' callbacks are never refused: a provider that gets a 503 might give up on telling
 * us an order was paid.
 */
@Configuration
class AppGateConfig {

    /** Ahead of Spring Security's chain (order -100). */
    private static final int ORDER = -200;

    @Bean
    FilterRegistrationBean<AppGateFilter> appGate(AppConfigService config, Clock clock) {
        FilterRegistrationBean<AppGateFilter> bean = new FilterRegistrationBean<>(new AppGateFilter(config, clock));
        bean.setOrder(ORDER);
        bean.addUrlPatterns("/api/*");
        return bean;
    }

    static class AppGateFilter extends OncePerRequestFilter {

        private final AppConfigService config;
        private final Clock clock;

        AppGateFilter(AppConfigService config, Clock clock) {
            this.config = config;
            this.clock = clock;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            String path = request.getRequestURI();
            String method = request.getMethod();
            boolean exempt = "OPTIONS".equals(method) || path.startsWith("/api/app/config") || path.startsWith("/api/payments/");
            if (!exempt) {
                boolean admin = path.startsWith("/api/admin/");
                boolean auth = path.startsWith("/api/auth/");
                String platform = request.getHeader("X-App-Platform");
                if (!admin && !auth && config.tooOld(platform, request.getHeader("X-App-Version"))) {
                    write(response, 426, "APP_UPDATE_REQUIRED", "Phiên bản ứng dụng đã cũ. Hãy cập nhật để tiếp tục.", config.storeUrl(platform), null);
                    return;
                }
                AppConfigViews.Maintenance m = config.maintenance();
                if (m.active() && !admin) {
                    boolean reads = "GET".equals(method) || "HEAD".equals(method);
                    if (m.level().equals("FULL")) {
                        write(response, 503, "MAINTENANCE", message(m), null, retryAfter(m));
                        return;
                    }
                    if (m.level().equals("READ_ONLY") && !reads && !auth) {
                        write(response, 503, "MAINTENANCE_READ_ONLY", message(m), null, retryAfter(m));
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }

        private String message(AppConfigViews.Maintenance m) {
            return m.message() == null ? "Hệ thống đang bảo trì. Vui lòng quay lại sau." : m.message();
        }

        private long retryAfter(AppConfigViews.Maintenance m) {
            if (m.endsAt() == null) {
                return 300;
            }
            return Math.max(30, Duration.between(clock.instant(), m.endsAt()).toSeconds());
        }

        private static void write(HttpServletResponse response, int status, String code, String detail, String storeUrl, Long retryAfter) throws IOException {
            response.setStatus(status);
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            if (retryAfter != null) {
                response.setHeader("Retry-After", Long.toString(retryAfter));
            }
            StringBuilder body = new StringBuilder("{\"status\":").append(status).append(",\"code\":\"").append(code).append("\",\"detail\":\"").append(escape(detail)).append('"');
            if (storeUrl != null) {
                body.append(",\"storeUrl\":\"").append(escape(storeUrl)).append('"');
            }
            response.getWriter().write(body.append('}').toString());
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

    }
}
