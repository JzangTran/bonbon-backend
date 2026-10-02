package com.bonbon.backend.common.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Google reCAPTCHA v2 server-side check (siteverify). Disabled with {@code bonbon.captcha.enabled=false}
 * for local development and tests, where every token is accepted.
 */
@Component
public class CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);
    private static final String SITEVERIFY = "https://www.google.com/recaptcha/api/siteverify";

    private final boolean enabled;
    private final String secret;
    private final RestClient http = RestClient.create();

    public CaptchaVerifier(@Value("${bonbon.captcha.enabled:true}") boolean enabled,
            @Value("${bonbon.captcha.secret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret;
        if (enabled && secret.isBlank()) {
            throw new IllegalStateException("bonbon.captcha.secret (BONBON_RECAPTCHA_SECRET) is required when captcha is enabled");
        }
    }

    public boolean isValid(String token, String remoteIp) {
        if (!enabled) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null) {
            form.add("remoteip", remoteIp);
        }
        try {
            Map<?, ?> result = http.post().uri(SITEVERIFY).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (RuntimeException e) {
            log.warn("reCAPTCHA verification failed to run", e);
            return false;
        }
    }
}
