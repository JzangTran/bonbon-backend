package com.bonbon.backend.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

import com.bonbon.backend.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * An upload whose real type was checked from its first bytes (the client's Content-Type and file name are
 * not trusted) and whose size is within the limit.
 */
public record ValidatedFile(byte[] content, FileType type) {

    public enum FileType {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        PDF("application/pdf", "pdf");

        private final String contentType;
        private final String extension;

        FileType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }
    }

    public static final Set<FileType> IMAGES = Set.of(FileType.JPEG, FileType.PNG, FileType.WEBP);
    public static final Set<FileType> IMAGES_AND_PDF = Set.of(FileType.JPEG, FileType.PNG, FileType.WEBP, FileType.PDF);

    public static ValidatedFile of(MultipartFile upload, Set<FileType> allowed, long maxBytes) {
        if (upload == null || upload.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "Vui lòng chọn tệp.");
        }
        if (upload.getSize() > maxBytes) {
            throw new BusinessException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                    "Tệp vượt quá " + (maxBytes / (1024 * 1024)) + " MB.");
        }
        byte[] bytes;
        try (InputStream in = upload.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILE_UNREADABLE", "Không đọc được tệp, vui lòng thử lại.");
        }
        FileType type = sniff(bytes);
        if (type == null || !allowed.contains(type)) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "Định dạng tệp không được hỗ trợ.");
        }
        return new ValidatedFile(bytes, type);
    }

    static FileType sniff(byte[] b) {
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) {
            return FileType.JPEG;
        }
        if (startsWith(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return FileType.PNG;
        }
        if (startsWith(b, 0, 'R', 'I', 'F', 'F') && startsWith(b, 8, 'W', 'E', 'B', 'P')) {
            return FileType.WEBP;
        }
        if (startsWith(b, 0, '%', 'P', 'D', 'F', '-')) {
            return FileType.PDF;
        }
        return null;
    }

    private static boolean startsWith(byte[] b, int offset, int... signature) {
        if (b.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((b[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ValidatedFile f && f.type == type && Arrays.equals(f.content, content);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(content) + type.hashCode();
    }

    @Override
    public String toString() {
        return "ValidatedFile[" + type + ", " + content.length + " bytes]";
    }
}
