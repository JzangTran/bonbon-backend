package com.bonbon.backend.payment.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RefundRequests {

    private RefundRequests() {
    }

    @Schema(name = "RefundDestinationRequest", description = "Tài khoản ngân hàng nhận hoàn tiền. Chỉ lưu trên khoản hoàn này, không lưu vào hồ sơ.")
    public record Destination(
            @Schema(description = "Tên ngân hàng.", example = "Vietcombank") @NotBlank @Size(max = 100) String bankName,
            @Schema(description = "Số tài khoản, 6–20 chữ số.", example = "0123456789") @NotBlank @Pattern(regexp = "[0-9]{6,20}", message = "Số tài khoản gồm 6–20 chữ số.") String accountNumber,
            @Schema(description = "Tên chủ tài khoản.", example = "NGUYEN VAN A") @NotBlank @Size(max = 100) String accountName) {
    }

    @Schema(name = "CompleteRefundRequest")
    public record Complete(
            @Schema(description = "Mã giao dịch của ngân hàng; mỗi mã chỉ dùng cho một khoản hoàn.") @NotBlank @Size(min = 3, max = 100) String bankReference,
            @Schema(description = "Thời điểm chuyển khoản; bỏ trống là lúc bấm xác nhận.") Instant transferredAt) {
    }

    @Schema(name = "FailRefundRequest")
    public record Fail(@Schema(description = "Vì sao chuyển không được (tài khoản đóng, sai số...). Khách sẽ được yêu cầu nhập tài khoản khác.") @NotBlank @Size(max = 300) String reason) {
    }
}
