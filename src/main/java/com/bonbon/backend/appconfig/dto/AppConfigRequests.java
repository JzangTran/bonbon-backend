package com.bonbon.backend.appconfig.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AppConfigRequests {

    private AppConfigRequests() {
    }

    @Schema(name = "SetVersionPolicyRequest")
    public record VersionPolicy(
            @Schema(description = "Bản thấp nhất còn được hỗ trợ, dạng 1.10.0 (so từng phần số, không so chuỗi). Chỉ nâng sau khi bản mới đã lên cả hai cửa hàng ứng dụng.")
            @NotNull @Pattern(regexp = "[0-9]+(\\.[0-9]+){0,3}", message = "Phiên bản phải có dạng 1.2.3.") String minSupportedVersion,
            @Schema(description = "Bản mới nhất; không thấp hơn bản tối thiểu.") @NotNull @Pattern(regexp = "[0-9]+(\\.[0-9]+){0,3}", message = "Phiên bản phải có dạng 1.2.3.") String latestVersion,
            @Size(max = 500) String storeUrl,
            @Size(max = 500) String message,
            @Schema(description = "Lý do thay đổi, được ghi lại.") @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "SetMaintenanceRequest")
    public record Maintenance(
            @NotNull @Pattern(regexp = "NONE|READ_ONLY|FULL", message = "Mức phải là NONE, READ_ONLY hoặc FULL.") String level,
            @Size(max = 500) String message,
            @Schema(description = "Giờ bắt đầu; bỏ trống là ngay.") Instant startsAt,
            @Schema(description = "Giờ kết thúc dự kiến; bỏ trống là chưa biết.") Instant endsAt,
            @Schema(description = "Lý do thay đổi, được ghi lại.") @NotBlank @Size(max = 500) String reason) {
    }
}
