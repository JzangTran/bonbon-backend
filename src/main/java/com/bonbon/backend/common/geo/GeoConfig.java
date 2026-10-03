package com.bonbon.backend.common.geo;

import java.net.http.HttpClient;
import java.time.Duration;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Goong when {@code bonbon.geo.goong.api-key} (env GOONG_API_KEY) is set; otherwise the fake geocoder, but
 * only where {@code bonbon.geo.fake-when-unconfigured} allows it (dev and test profiles). Production without
 * a key fails at startup instead of silently storing made-up coordinates.
 */
@Configuration(proxyBeanMethods = false)
class GeoConfig {

    private static final Logger log = LoggerFactory.getLogger(GeoConfig.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    Geocoder geocoder(@Value("${bonbon.geo.goong.api-key:}") String apiKey,
            @Value("${bonbon.geo.goong.base-url:https://rsapi.goong.io}") String baseUrl,
            @Value("${bonbon.geo.fake-when-unconfigured:false}") boolean fakeAllowed,
            RateLimiter rateLimiter) {
        if (!apiKey.isBlank()) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
            factory.setReadTimeout(TIMEOUT);
            return new GoongGeocoder(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), apiKey, rateLimiter);
        }
        if (fakeAllowed) {
            log.warn("GOONG_API_KEY is not set: address suggestions come from the fake geocoder");
            return new FakeGeocoder();
        }
        throw new IllegalStateException("bonbon.geo.goong.api-key (env GOONG_API_KEY) must be set");
    }
}
