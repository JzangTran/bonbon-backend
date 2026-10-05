package com.bonbon.backend.notification.service;

import com.bonbon.backend.notification.gateway.ExpoPushGateway;
import com.bonbon.backend.notification.gateway.FakePushGateway;
import com.bonbon.backend.notification.gateway.PushGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** {@code bonbon.push.provider=expo} sends for real; anything else (the default) only records what would be sent. */
@Configuration
class PushConfig {

    @Bean
    PushGateway pushGateway(@Value("${bonbon.push.provider:fake}") String provider,
            @Value("${bonbon.push.expo.url:https://exp.host/--/api/v2/push/send}") String url,
            @Value("${bonbon.push.expo.access-token:}") String accessToken) {
        if (!"expo".equals(provider)) {
            return new FakePushGateway();
        }
        RestClient.Builder builder = RestClient.builder().baseUrl(url).defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (!accessToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return new ExpoPushGateway(builder.build());
    }
}
