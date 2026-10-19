package com.bonbon.backend.common.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.storage.ValidatedFile.FileType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSanitizerTests {

    /** A real 1 x 1 PNG. */
    private static final byte[] PNG_1X1 = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    // ---- PNG ---------------------------------------------------------------------------------------------------------

    @Test
    void aPlainPngPassesUnchanged() {
        assertThat(ImageSanitizer.clean(PNG_1X1, FileType.PNG)).isEqualTo(PNG_1X1);
    }

    @Test
    void pngTextAndExifChunksAreCutOut() {
        byte[] withText = insertPngChunk(PNG_1X1, "tEXt", "Location\0 21.0285 N, 105.8542 E".getBytes(StandardCharsets.ISO_8859_1));
        byte[] withExif = insertPngChunk(withText, "eXIf", new byte[] {1, 2, 3, 4});
        assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains("tEXt").contains("eXIf");

        byte[] cleaned = ImageSanitizer.clean(withExif, FileType.PNG);

        assertThat(new String(cleaned, StandardCharsets.ISO_8859_1)).doesNotContain("tEXt").doesNotContain("eXIf").contains("IDAT").contains("IEND");
    }

    @Test
    void aPngDeclaringAHugeCanvasIsRejected() {
        byte[] huge = PNG_1X1.clone();
        putInt(huge, 16, 60_000);
        putInt(huge, 20, 60_000);
        assertThatThrownBy(() -> ImageSanitizer.clean(huge, FileType.PNG))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_DIMENSIONS_TOO_LARGE"));
    }

    @Test
    void aTruncatedPngIsRejected() {
        byte[] cut = java.util.Arrays.copyOf(PNG_1X1, PNG_1X1.length - 20);
        assertThatThrownBy(() -> ImageSanitizer.clean(cut, FileType.PNG))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_INVALID"));
    }

    @Test
    void aPngWhoseFirstChunkIsNotTheHeaderIsRejected() {
        byte[] bad = PNG_1X1.clone();
        bad[12] = 'X';
        assertThatThrownBy(() -> ImageSanitizer.clean(bad, FileType.PNG)).isInstanceOf(BusinessException.class);
    }

    // ---- JPEG --------------------------------------------------------------------------------------------------------

    @Test
    void jpegExifAndCommentSegmentsAreCutOutAndPixelsKept() {
        byte[] jpeg = jpeg(120, 80, true);
        assertThat(new String(jpeg, StandardCharsets.ISO_8859_1)).contains("Exif").contains("GPS");

        byte[] cleaned = ImageSanitizer.clean(jpeg, FileType.JPEG);

        String text = new String(cleaned, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif").doesNotContain("GPS").doesNotContain("hello");
        assertThat(cleaned).endsWith(new byte[] {0x11, 0x22, 0x33, (byte) 0xFF, (byte) 0xD9}); // scan data and EOI untouched
        assertThat(cleaned.length).isLessThan(jpeg.length);
    }

    @Test
    void aJpegWithOversizedDimensionsIsRejected() {
        assertThatThrownBy(() -> ImageSanitizer.clean(jpeg(20_000, 20_000, false), FileType.JPEG))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_DIMENSIONS_TOO_LARGE"));
    }

    @Test
    void aJpegWithoutAFrameIsRejected() {
        byte[] noFrame = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        assertThatThrownBy(() -> ImageSanitizer.clean(noFrame, FileType.JPEG)).isInstanceOf(BusinessException.class);
    }

    // ---- WebP --------------------------------------------------------------------------------------------------------

    @Test
    void webpExifIsCutOutAndTheRiffSizeFixed() {
        byte[] webp = webpLossless(64, 48, true);
        assertThat(new String(webp, StandardCharsets.ISO_8859_1)).contains("EXIF");

        byte[] cleaned = ImageSanitizer.clean(webp, FileType.WEBP);

        assertThat(new String(cleaned, StandardCharsets.ISO_8859_1)).doesNotContain("EXIF").contains("VP8L");
        long riff = (cleaned[4] & 0xFFL) | ((cleaned[5] & 0xFFL) << 8) | ((cleaned[6] & 0xFFL) << 16) | ((cleaned[7] & 0xFFL) << 24);
        assertThat(riff).isEqualTo(cleaned.length - 8L);
    }

    @Test
    void aWebpWithOversizedDimensionsIsRejected() {
        assertThatThrownBy(() -> ImageSanitizer.clean(webpLossless(16_000, 16_000, false), FileType.WEBP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_DIMENSIONS_TOO_LARGE"));
    }

    // ---- PDF ---------------------------------------------------------------------------------------------------------

    @Test
    void aPlainPdfPasses() {
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(ImageSanitizer.clean(pdf, FileType.PDF)).isEqualTo(pdf);
    }

    @Test
    void aPdfWithAScriptIsRejected() {
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<< /S /JavaScript /JS (app.alert(1)) >>\nendobj\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ImageSanitizer.clean(pdf, FileType.PDF))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("PDF_ACTIVE_CONTENT"));
    }

    @Test
    void aPdfWithoutAnEndMarkerIsRejected() {
        byte[] pdf = "%PDF-1.4\nnothing else".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> ImageSanitizer.clean(pdf, FileType.PDF)).isInstanceOf(BusinessException.class);
    }

    // ---- builders ----------------------------------------------------------------------------------------------------

    private static void putInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >> 24);
        b[offset + 1] = (byte) (value >> 16);
        b[offset + 2] = (byte) (value >> 8);
        b[offset + 3] = (byte) value;
    }

    /** Puts a chunk (with a dummy CRC) right after IHDR. */
    private static byte[] insertPngChunk(byte[] png, String name, byte[] data) {
        int afterHeader = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterHeader);
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);
        out.writeBytes(name.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(data);
        out.writeBytes(new byte[4]);
        out.write(png, afterHeader, png.length - afterHeader);
        return out.toByteArray();
    }

    private static byte[] jpeg(int width, int height, boolean withMetadata) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}); // JFIF
        if (withMetadata) {
            byte[] exif = "Exif\0\0GPSLatitude".getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2)});
            out.writeBytes(exif);
            byte[] comment = "hello".getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
            out.writeBytes(comment);
        }
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xC0, 0, 11, 8, (byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width, 1, 1, 0x11, 0});
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0, 0, 63, 0});
        out.writeBytes(new byte[] {0x11, 0x22, 0x33, (byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static byte[] webpLossless(int width, int height, boolean withExif) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        long bits = (width - 1) | ((long) (height - 1) << 14);
        byte[] vp8l = {0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24), 0, 0, 0};
        chunks.writeBytes("VP8L".getBytes(StandardCharsets.ISO_8859_1));
        chunks.writeBytes(new byte[] {(byte) vp8l.length, 0, 0, 0});
        chunks.writeBytes(vp8l);
        if (withExif) {
            byte[] exif = "Exif\0\0GPS!".getBytes(StandardCharsets.ISO_8859_1); // 10 bytes
            chunks.writeBytes("EXIF".getBytes(StandardCharsets.ISO_8859_1));
            chunks.writeBytes(new byte[] {(byte) exif.length, 0, 0, 0});
            chunks.writeBytes(exif);
        }
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.ISO_8859_1));
        long size = 4 + body.length;
        out.writeBytes(new byte[] {(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
        out.writeBytes("WEBP".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(body);
        return out.toByteArray();
    }
}
