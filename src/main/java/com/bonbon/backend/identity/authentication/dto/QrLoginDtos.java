package com.bonbon.backend.identity.authentication.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Requests and views of the QR sign-in handshake (web shows a code, the seller's phone confirms it). */
public final class QrLoginDtos {

    private QrLoginDtos() {
    }

    /** A token made by {@code SecureTokens}: 43 URL-safe base64 characters. */
    public static final String TOKEN_PATTERN = "^[A-Za-z0-9_-]{43}$";

    @Schema(description = "Phiên đăng nhập bằng mã QR vừa tạo trên web.")
    public record Started(
            @Schema(description = "Mã của phiên, nằm trong mã QR.") String sessionId,
            @Schema(description = "Bí mật chỉ trình duyệt giữ, dùng để hỏi trạng thái và lấy token; không nằm trong mã QR.") String pollToken,
            @Schema(description = "Nội dung cần vẽ thành mã QR.", example = "bonbon://qr-login?s=...") String qrContent,
            @Schema(description = "Số giây còn hiệu lực.") long expiresInSeconds) {
    }

    @Schema(description = "Trình duyệt hỏi trạng thái hoặc đổi phiên đã xác nhận lấy token.")
    public record Poll(
            @NotBlank @Pattern(regexp = TOKEN_PATTERN, message = "Mã phiên không hợp lệ.") String sessionId,
            @NotBlank @Pattern(regexp = TOKEN_PATTERN, message = "Mã bí mật không hợp lệ.") String pollToken) {
    }

    @Schema(description = "Điện thoại gửi mã phiên đọc được từ mã QR.")
    public record Scan(
            @NotBlank @Pattern(regexp = TOKEN_PATTERN, message = "Mã QR không phải của bonbon hoặc đã hỏng.") String sessionId) {
    }

    public enum State {
        @Schema(description = "Chưa ai quét.") PENDING,
        @Schema(description = "Đã quét, đang chờ người bán xác nhận trên điện thoại.") SCANNED,
        @Schema(description = "Đã xác nhận; trình duyệt gọi exchange để lấy token.") CONFIRMED,
        @Schema(description = "Người bán đã từ chối.") REJECTED,
        @Schema(description = "Hết hạn hoặc đã dùng; tạo mã mới.") EXPIRED
    }

    @Schema(description = "Trạng thái phiên mà trình duyệt thấy.")
    public record Status(State state) {
    }

    @Schema(description = "Thông tin của trình duyệt đang chờ đăng nhập, cho người bán đối chiếu trước khi xác nhận.")
    public record Inspect(
            @Schema(description = "Trình duyệt và hệ điều hành, đọc từ User-Agent.", example = "Chrome trên Windows") String device,
            @Schema(description = "Địa chỉ IP của trình duyệt.") String ip,
            @Schema(description = "Lúc trình duyệt tạo mã.") Instant requestedAt,
            @Schema(description = "Lúc mã hết hạn.") Instant expiresAt) {
    }
}
