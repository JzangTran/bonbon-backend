package com.bonbon.backend.common.security;

import java.io.IOException;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless API security: every request carries a signed access token, except the public paths below.
 * Authorities are the {@code <resource>:<action>} permissions from the token's {@code permissions} claim,
 * checked with {@code @PreAuthorize("hasAuthority('order:read')")}.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, CorsProperties.class})
class SecurityConfig {

    static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api/public/**",
            "/api/auth/**",
            "/api/legal/documents/**",
            "/api/admin/auth/**"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Reading the category tree is public (browsing filters); writes live under /api/admin.
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        // Guests browse shops and menus before logging in (browse-vendors-in-area.md).
                        .requestMatchers(HttpMethod.GET, "/api/vendors", "/api/vendors/*/menu").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(permissionsConverter()))
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                                        "Vui lòng đăng nhập để tiếp tục."))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                                        "Bạn không có quyền thực hiện thao tác này.")))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                        writeProblem(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                                "Vui lòng đăng nhập để tiếp tục.")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties props, List<TokenRevocationCheck> revocationChecks) {
        SecretKeySpec key = new SecretKeySpec(props.secretBytes(), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> notRevoked = jwt -> revocationChecks.stream().anyMatch(c -> c.isRevoked(jwt))
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token revoked", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), notRevoked));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties props) {
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(props.secretBytes(), "HmacSHA256")).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Client-Channel", "X-App-Version"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    private static JwtAuthenticationConverter permissionsConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> perms = jwt.getClaimAsStringList(CurrentPrincipal.CLAIM_PERMISSIONS);
            if (perms == null) {
                return List.of();
            }
            return perms.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        });
        return converter;
    }

    private static void writeProblem(HttpServletResponse res, int status, String code, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType("application/problem+json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
