package com.bonbon.backend.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage over the S3 API. Production: Cloudflare R2 (endpoint
 * {@code https://<account-id>.r2.cloudflarestorage.com}, region {@code auto}); development: S3Mock from
 * deploy/compose.dev.yaml. Keys come from the environment, never from the repository.
 *
 * @param publicBaseUrl the URL that serves {@code publicBucket} to browsers and apps
 */
@ConfigurationProperties("bonbon.storage")
public record StorageProperties(
        String endpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("bonbon-private") String privateBucket,
        @DefaultValue("bonbon-public") String publicBucket,
        String publicBaseUrl) {

    public StorageProperties {
        if (isBlank(endpoint) || isBlank(accessKey) || isBlank(secretKey)) {
            throw new IllegalStateException(
                    "bonbon.storage.endpoint, access-key and secret-key (env BONBON_STORAGE_*) must be set");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
