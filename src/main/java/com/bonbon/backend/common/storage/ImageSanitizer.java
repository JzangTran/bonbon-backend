package com.bonbon.backend.common.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * Looks inside an upload that already passed the first-bytes check: the picture must be well formed and not absurdly
 * large (a few kilobytes of PNG can declare a 60000 x 60000 canvas that crashes whoever draws it), and metadata that
 * can leak where it was taken (EXIF GPS, XMP, text chunks) is cut out without re-encoding, so pixels stay untouched.
 * PDFs are only checked for structure and for plainly named active content (a script inside a compressed object stream
 * is not seen; PDFs are served as downloads, never rendered in the page).
 */
final class ImageSanitizer {

    /** Longest side in pixels. Phone cameras go up to about 12000 on the 200 MP modes; 10000 is generous for a dish photo. */
    static final int MAX_SIDE = 10_000;
    /** Whole picture, in pixels (a 48 MP photo fits). */
    static final long MAX_PIXELS = 50_000_000L;

    private ImageSanitizer() {
    }

    /** The upload as it should be stored: same picture, no location metadata. */
    static byte[] clean(byte[] bytes, ValidatedFile.FileType type) {
        return switch (type) {
            case JPEG -> jpeg(bytes);
            case PNG -> png(bytes);
            case WEBP -> webp(bytes);
            case PDF -> pdf(bytes);
        };
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "IMAGE_INVALID", "Tệp ảnh bị hỏng hoặc không hợp lệ.");
    }

    private static void checkSize(long width, long height) {
        if (width < 1 || height < 1) {
            throw invalid();
        }
        if (width > MAX_SIDE || height > MAX_SIDE || width * height > MAX_PIXELS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IMAGE_DIMENSIONS_TOO_LARGE",
                    "Ảnh quá lớn (tối đa " + MAX_SIDE + " px mỗi cạnh). Hãy chọn ảnh nhỏ hơn.");
        }
    }

    // ---- JPEG --------------------------------------------------------------------------------------------------------

    private static byte[] jpeg(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        boolean sawFrame = false;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                throw invalid();
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // no length
                out.write(0xFF);
                out.write(marker);
                i += 2;
                continue;
            }
            if (marker == 0xD9) { // EOI
                out.write(0xFF);
                out.write(0xD9);
                return sawFrame ? out.toByteArray() : fail();
            }
            int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (length < 2 || i + 2 + length > b.length) {
                throw invalid();
            }
            boolean frame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (frame) {
                if (length < 8) {
                    throw invalid();
                }
                int height = ((b[i + 5] & 0xFF) << 8) | (b[i + 6] & 0xFF);
                int width = ((b[i + 7] & 0xFF) << 8) | (b[i + 8] & 0xFF);
                checkSize(width, height);
                sawFrame = true;
            }
            boolean drop = marker == 0xE1 /* EXIF, XMP */ || marker == 0xED /* Photoshop IRB */ || marker == 0xFE /* comment */;
            if (!drop) {
                out.write(b, i, 2 + length);
            }
            i += 2 + length;
            if (marker == 0xDA) { // start of scan: the entropy-coded data and the rest follow untouched
                if (!sawFrame) {
                    throw invalid();
                }
                out.write(b, i, b.length - i);
                return out.toByteArray();
            }
        }
        throw invalid();
    }

    private static byte[] fail() {
        throw invalid();
    }

    // ---- PNG ---------------------------------------------------------------------------------------------------------

    private static byte[] png(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, 8);
        int i = 8;
        boolean sawHeader = false;
        boolean sawEnd = false;
        while (i + 12 <= b.length && !sawEnd) {
            long length = u32(b, i);
            if (length > b.length - i - 12L) {
                throw invalid();
            }
            String name = new String(b, i + 4, 4, StandardCharsets.ISO_8859_1);
            int total = (int) length + 12;
            if (!sawHeader) {
                if (!name.equals("IHDR") || length != 13) {
                    throw invalid();
                }
                checkSize(u32(b, i + 8), u32(b, i + 12));
                sawHeader = true;
            }
            boolean drop = name.equals("tEXt") || name.equals("iTXt") || name.equals("zTXt") || name.equals("eXIf");
            if (!drop) {
                out.write(b, i, total);
            }
            if (name.equals("IEND")) {
                sawEnd = true;
            }
            i += total;
        }
        if (!sawHeader || !sawEnd) {
            throw invalid();
        }
        return out.toByteArray();
    }

    // ---- WebP --------------------------------------------------------------------------------------------------------

    private static byte[] webp(byte[] b) {
        if (b.length < 20) {
            throw invalid();
        }
        long riffSize = u32le(b, 4);
        if (riffSize + 8 > b.length || riffSize < 12) {
            throw invalid();
        }
        int end = (int) riffSize + 8;
        ByteArrayOutputStream body = new ByteArrayOutputStream(b.length);
        int i = 12;
        boolean sawImage = false;
        while (i + 8 <= end) {
            String name = new String(b, i, 4, StandardCharsets.ISO_8859_1);
            long length = u32le(b, i + 4);
            long padded = length + (length & 1);
            if (i + 8 + padded > end) {
                throw invalid();
            }
            int data = i + 8;
            switch (name) {
                case "VP8X" -> {
                    if (length < 10) {
                        throw invalid();
                    }
                    long width = 1 + u24le(b, data + 4);
                    long height = 1 + u24le(b, data + 7);
                    checkSize(width, height);
                    sawImage = true;
                }
                case "VP8 " -> {
                    if (length < 10 || (b[data + 3] & 0xFF) != 0x9D || (b[data + 4] & 0xFF) != 0x01 || (b[data + 5] & 0xFF) != 0x2A) {
                        throw invalid();
                    }
                    checkSize(((b[data + 6] & 0xFF) | ((b[data + 7] & 0x3F) << 8)), ((b[data + 8] & 0xFF) | ((b[data + 9] & 0x3F) << 8)));
                    sawImage = true;
                }
                case "VP8L" -> {
                    if (length < 5 || (b[data] & 0xFF) != 0x2F) {
                        throw invalid();
                    }
                    long bits = u32le(b, data + 1);
                    checkSize((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
                    sawImage = true;
                }
                default -> {
                }
            }
            if (!name.equals("EXIF") && !name.equals("XMP ")) {
                byte[] chunk = new byte[(int) (8 + padded)];
                System.arraycopy(b, i, chunk, 0, chunk.length);
                if (name.equals("VP8X")) {
                    chunk[8] &= (byte) ~(0x08 | 0x04); // the EXIF and XMP flags
                }
                body.write(chunk, 0, chunk.length);
            }
            i += 8 + (int) padded;
        }
        if (!sawImage) {
            throw invalid();
        }
        byte[] chunks = body.toByteArray();
        byte[] out = new byte[12 + chunks.length];
        System.arraycopy(b, 0, out, 0, 12);
        System.arraycopy(chunks, 0, out, 12, chunks.length);
        long size = out.length - 8L;
        out[4] = (byte) size;
        out[5] = (byte) (size >> 8);
        out[6] = (byte) (size >> 16);
        out[7] = (byte) (size >> 24);
        return out;
    }

    // ---- PDF ---------------------------------------------------------------------------------------------------------

    private static byte[] pdf(byte[] b) {
        String tail = new String(b, Math.max(0, b.length - 1024), Math.min(b.length, 1024), StandardCharsets.ISO_8859_1);
        if (!tail.contains("%%EOF")) {
            throw invalid();
        }
        String text = new String(b, StandardCharsets.ISO_8859_1);
        for (String active : new String[] {"/JavaScript", "/Launch", "/EmbeddedFile", "/OpenAction"}) {
            int at = text.indexOf(active);
            // a longer name that merely starts the same (for example "/LaunchPad") is not active content
            while (at >= 0) {
                int next = at + active.length();
                char c = next < text.length() ? text.charAt(next) : ' ';
                if (!Character.isLetterOrDigit(c)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "PDF_ACTIVE_CONTENT",
                            "Tệp PDF có nội dung chạy được (script, tệp đính kèm) nên không được nhận.");
                }
                at = text.indexOf(active, next);
            }
        }
        return b;
    }

    // ---- bytes -------------------------------------------------------------------------------------------------------

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    private static long u32le(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long u24le(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16);
    }
}
