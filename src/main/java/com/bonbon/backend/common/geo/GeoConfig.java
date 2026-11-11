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
 * Which address and road services to use, {@code bonbon.geo.provider}: {@code osm} (default, OpenStreetMap through Photon and OSRM,
 * no key), {@code goong} (needs GOONG_API_KEY, addresses only), {@code openmap} (OpenMap.vn, needs OPENMAP_API_KEY, addresses only)
 * or {@code fake} (allowed only where
 * {@code bonbon.geo.fake-when-unconfigured} says so: never made-up coordinates in production).
 */
@Configuration(proxyBeanMethods = false)
class GeoConfig {

    private static final Logger log = LoggerFactory.getLogger(GeoConfig.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    Geocoder geocoder(@Value("${bonbon.geo.provider:osm}") String provider,
            @Value("${bonbon.geo.goong.api-key:}") String apiKey,
            @Value("${bonbon.geo.goong.base-url:https://rsapi.goong.io}") String baseUrl,
            @Value("${bonbon.geo.photon-url:https://photon.komoot.io}") String photonUrl,
            @Value("${bonbon.geo.openmap.api-key:}") String openmapKey,
            @Value("${bonbon.geo.openmap.base-url:https://mapapis.openmap.vn/v1}") String openmapUrl,
            @Value("${bonbon.geo.fake-when-unconfigured:false}") boolean fakeAllowed,
            RateLimiter rateLimiter) {
        return switch (provider) {
            case "goong" -> {
                if (apiKey.isBlank()) {
                    throw new IllegalStateException("bonbon.geo.goong.api-key (env GOONG_API_KEY) must be set");
                }
                yield new GoongGeocoder(client(baseUrl), apiKey, rateLimiter);
            }
            case "openmap" -> {
                if (openmapKey.isBlank()) {
                    throw new IllegalStateException("bonbon.geo.openmap.api-key (env OPENMAP_API_KEY) must be set");
                }
                yield new OpenMapGeocoder(client(openmapUrl), openmapKey, rateLimiter);
            }
            case "fake" -> {
                requireFakeAllowed(fakeAllowed);
                log.warn("Address suggestions come from the fake geocoder");
                yield new FakeGeocoder();
            }
            default -> new OsmGeocoder(client(photonUrl), rateLimiter);
        };
    }

    @Bean
    RoutePlanner routePlanner(@Value("${bonbon.geo.provider:osm}") String provider,
            @Value("${bonbon.geo.osrm-url:https://routing.openstreetmap.de/routed-car}") String osrmUrl,
            @Value("${bonbon.geo.fake-when-unconfigured:false}") boolean fakeAllowed,
            RateLimiter rateLimiter) {
        if ("fake".equals(provider)) {
            requireFakeAllowed(fakeAllowed);
            return new FakeRoutePlanner();
        }
        return new OsrmRoutePlanner(client(osrmUrl), rateLimiter);
    }

    private static void requireFakeAllowed(boolean fakeAllowed) {
        if (!fakeAllowed) {
            throw new IllegalStateException("bonbon.geo.provider=fake is not allowed here");
        }
    }

    /** The public OSM services refuse a stock HTTP client: every request says which application it is. */
    private static RestClient client(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).defaultHeader("User-Agent", "bonbon-backend/1.0")
                // the routing server answers "deflate" in a form the JDK cannot read
                .defaultHeader("Accept-Encoding", "identity").build();
    }
}
