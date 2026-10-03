package com.bonbon.backend.common.storage;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * {@link ObjectStorage} over the S3 API with path-style addressing, which both R2 and S3Mock accept.
 * Keys look like {@code <prefix>/2026/10/<uuid>.<ext>}: unguessable, and never derived from user input.
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
class S3ObjectStorage implements ObjectStorage {

    private final StorageProperties props;
    private final S3Client s3;
    private final S3Presigner presigner;

    S3ObjectStorage(StorageProperties props) {
        this.props = props;
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    @Override
    public String put(Visibility visibility, String prefix, ValidatedFile file) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String key = "%s/%d/%02d/%s.%s".formatted(prefix, today.getYear(), today.getMonthValue(), UUID.randomUUID(),
                file.type().extension());
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket(visibility))
                .key(key)
                .contentType(file.type().contentType())
                .build(), RequestBody.fromBytes(file.content()));
        return key;
    }

    @Override
    public String signedUrl(String key, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(props.privateBucket()).key(key).build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    @Override
    public String publicUrl(String key) {
        String base = props.publicBaseUrl();
        return (base.endsWith("/") ? base : base + "/") + key;
    }

    @Override
    public void delete(Visibility visibility, String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket(visibility)).key(key).build());
    }

    @PreDestroy
    void close() {
        presigner.close();
        s3.close();
    }

    byte[] read(Visibility visibility, String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket(visibility)).key(key).build()).asByteArray();
    }

    private String bucket(Visibility visibility) {
        return visibility == Visibility.PRIVATE ? props.privateBucket() : props.publicBucket();
    }
}
