package com.bonbon.backend.payment.service;

import java.time.Duration;

import com.bonbon.backend.payment.gateway.FakePaymentGateway;
import com.bonbon.backend.payment.gateway.MomoPaymentGateway;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code bonbon.payment.provider=momo} talks to MoMo (test environment by default); anything else, the default, is a
 * fake that never leaves the machine. The fake signs with fixed development keys, which would let anyone forge a
 * payment, so it refuses to start under the {@code prod} profile.
 */
@Configuration
class PaymentConfig {

    static final String FAKE_ACCESS_KEY = "bonbon-dev-access-key";
    static final String FAKE_SECRET_KEY = "bonbon-dev-secret-key";
    static final String FAKE_PARTNER_CODE = "BONBONDEV";

    @Bean
    MomoSettings momoSettings(@Value("${bonbon.payment.provider:fake}") String provider, Environment environment,
            @Value("${bonbon.payment.momo.endpoint:https://test-payment.momo.vn}") String endpoint,
            @Value("${bonbon.payment.momo.partner-code:}") String partnerCode,
            @Value("${bonbon.payment.momo.access-key:}") String accessKey,
            @Value("${bonbon.payment.momo.secret-key:}") String secretKey,
            @Value("${bonbon.payment.momo.redirect-url:${bonbon.app.web-url:http://localhost:5173}/payment/return}") String redirectUrl,
            @Value("${bonbon.payment.momo.ipn-url:http://localhost:8080/api/payments/momo/ipn}") String ipnUrl) {
        if (!"momo".equals(provider)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("bonbon.payment.provider must be momo in production");
            }
            return new MomoSettings(endpoint, partnerCode.isBlank() ? FAKE_PARTNER_CODE : partnerCode,
                    accessKey.isBlank() ? FAKE_ACCESS_KEY : accessKey, secretKey.isBlank() ? FAKE_SECRET_KEY : secretKey, redirectUrl, ipnUrl);
        }
        return new MomoSettings(endpoint, partnerCode, accessKey, secretKey, redirectUrl, ipnUrl);
    }

    @Bean
    MomoSigner momoSigner(MomoSettings settings) {
        return new MomoSigner(settings.accessKey(), settings.secretKey());
    }

    @Bean
    PaymentGateway paymentGateway(@Value("${bonbon.payment.provider:fake}") String provider, MomoSettings settings, MomoSigner signer,
            @Value("${bonbon.payment.fake.base-url:http://localhost:8080}") String fakeBaseUrl) {
        if (!"momo".equals(provider)) {
            return new FakePaymentGateway(fakeBaseUrl);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        // MoMo states 30 s as the minimum timeout for its APIs.
        factory.setReadTimeout(Duration.ofSeconds(30));
        RestClient client = RestClient.builder().baseUrl(settings.endpoint()).requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).build();
        return new MomoPaymentGateway(client, settings, signer);
    }
}
