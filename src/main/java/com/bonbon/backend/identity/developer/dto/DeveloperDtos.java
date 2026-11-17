package com.bonbon.backend.identity.developer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.validation.PersonName;
import com.bonbon.backend.common.validation.RealEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The developer portal: asking for access to the API reference, and the administrators' decisions. */
public final class DeveloperDtos {

    private DeveloperDtos() {
    }

    @Schema(name = "DeveloperAccessRequestCreate", description = "Gửi đội ngũ phát triển; quản trị viên duyệt rồi mới vào được trang tài liệu API.")
    public record Create(
            @NotBlank @PersonName @Size(max = 100) String name,
            @NotBlank @RealEmail @Size(max = 254) String email,
            @Schema(description = "Công ty hoặc nhóm, không bắt buộc.") @Size(max = 150) String company,
            @Schema(description = "Trang web hoặc kho mã, không bắt buộc; phải bắt đầu bằng https://.")
            @Size(max = 300) @Pattern(regexp = "^$|https://\\S+", message = "Địa chỉ phải bắt đầu bằng https://") String website,
            @Schema(description = "Bạn định dùng API để làm gì, 20–1000 ký tự.") @NotBlank @Size(min = 20, max = 1000) String purpose,
            String captchaToken) {
    }

    @Schema(name = "DeveloperAccessRequestReceived")
    public record Received(UUID id, String status) {
    }

    @Schema(name = "DeveloperLogin")
    public record Login(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 100) String password, String captchaToken) {
    }

    @Schema(name = "AdminDeveloperAccessRequest", description = "`status`: PENDING, APPROVED, REJECTED, REVOKED.")
    public record AdminView(UUID id, String name, String email, String company, String website, String purpose, String status, String reason,
            UUID userId, @Schema(description = "Với yêu cầu đang chờ: email đã có tài khoản bonbon (khách hoặc người bán), duyệt sẽ thêm vai trò vào tài khoản đó.") boolean existingAccount,
            Instant createdAt, Instant decidedAt) {
    }

    @Schema(name = "AdminDeveloperAccessPage")
    public record Page(List<AdminView> items, int page, int size, long total) {
    }

    @Schema(name = "DeveloperAccessReject")
    public record Reason(@Schema(description = "Người gửi đọc được lý do, 1–500 ký tự.") @NotBlank @Size(max = 500) String reason) {
    }
}
