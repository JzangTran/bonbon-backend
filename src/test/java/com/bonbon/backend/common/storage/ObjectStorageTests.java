package com.bonbon.backend.common.storage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.bonbon.backend.TestcontainersConfiguration;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.storage.ObjectStorage.Visibility;
import com.bonbon.backend.common.storage.ValidatedFile.FileType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ObjectStorageTests {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes();

    @Autowired
    ObjectStorage storage;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void privateObjectIsReadableThroughASignedUrl() throws Exception {
        ValidatedFile file = ValidatedFile.of(new MockMultipartFile("f", "cccd.png", "image/png", PNG), ValidatedFile.IMAGES, 1024);
        String key = storage.put(Visibility.PRIVATE, "vendor-identity", file);

        assertThat(key).matches("vendor-identity/\\d{4}/\\d{2}/[0-9a-f-]{36}\\.png");
        String url = storage.signedUrl(key, Duration.ofMinutes(5));
        assertThat(url).contains("X-Amz-Signature=").contains("X-Amz-Expires=300");
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(PNG);

        storage.delete(Visibility.PRIVATE, key);
        assertThat(((S3ObjectStorage) storage)).satisfies(s ->
                assertThatThrownBy(() -> s.read(Visibility.PRIVATE, key)).isInstanceOf(RuntimeException.class));
    }

    @Test
    void publicObjectHasAStableUrl() throws Exception {
        ValidatedFile file = ValidatedFile.of(new MockMultipartFile("f", "licence.pdf", "application/pdf", PDF),
                ValidatedFile.IMAGES_AND_PDF, 1024);
        String key = storage.put(Visibility.PUBLIC, "menu", file);

        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(storage.publicUrl(key))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
    }

    @Test
    void typeComesFromTheBytesNotTheNameOrHeader() {
        MockMultipartFile disguised = new MockMultipartFile("f", "anh.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes());
        assertThatThrownBy(() -> ValidatedFile.of(disguised, ValidatedFile.IMAGES, 1024))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE"));

        MockMultipartFile pdfAsImage = new MockMultipartFile("f", "anh.png", "image/png", PDF);
        assertThatThrownBy(() -> ValidatedFile.of(pdfAsImage, ValidatedFile.IMAGES, 1024))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void sizeAndEmptinessAreChecked() {
        assertThatThrownBy(() -> ValidatedFile.of(new MockMultipartFile("f", "a.png", "image/png", PNG), ValidatedFile.IMAGES, 8))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_TOO_LARGE"));
        assertThatThrownBy(() -> ValidatedFile.of(new MockMultipartFile("f", new byte[0]), ValidatedFile.IMAGES, 1024))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("FILE_REQUIRED"));
    }

    @Test
    void sniffsEachSupportedType() {
        assertThat(ValidatedFile.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).isEqualTo(FileType.JPEG);
        assertThat(ValidatedFile.sniff(PNG)).isEqualTo(FileType.PNG);
        assertThat(ValidatedFile.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes())).isEqualTo(FileType.WEBP);
        assertThat(ValidatedFile.sniff(PDF)).isEqualTo(FileType.PDF);
        assertThat(ValidatedFile.sniff(new byte[] {1, 2})).isNull();
    }
}
