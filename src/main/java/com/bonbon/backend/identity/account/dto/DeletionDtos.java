package com.bonbon.backend.identity.account.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class DeletionDtos {

    private DeletionDtos() {
    }

    @Schema(name = "AccountDeletionBlocker")
    public record Blocker(
            @Schema(description = "ORDERS_IN_PROGRESS, OPEN_CASES, OPEN_TICKETS hoặc SHOP_NOT_CLOSED.") String code,
            String message,
            @Schema(description = "Nơi xử lý: ORDERS, ORDER_CASES, SUPPORT_TICKETS hoặc CLOSE_SHOP.") String link,
            long count) {
    }

    @Schema(name = "AccountDeletionCheck")
    public record Check(
            @Schema(description = "Có thể xoá ngay bây giờ: không còn gì chặn.") boolean allowed,
            List<Blocker> blockers,
            @Schema(description = "Số ngày chờ trước khi xoá thật; đăng nhập lại trong thời gian này sẽ huỷ việc xoá.") int graceDays,
            @Schema(description = "Tài khoản dùng mật khẩu thì phải nhập lại mật khẩu để xoá; tài khoản chỉ đăng nhập bằng Google thì không.") boolean needsPassword,
            @Schema(description = "Từ cần gõ để xác nhận: `DELETE` hoặc đúng email đăng nhập.") String confirmationEmail) {
    }

    @Schema(name = "DeleteAccountRequest")
    public record Delete(
            @Schema(description = "Gõ `DELETE` hoặc đúng email của tài khoản.") @NotBlank @Size(max = 255) String confirmation,
            @Schema(description = "Mật khẩu hiện tại; bắt buộc với tài khoản có mật khẩu.") @Size(max = 100) String password) {
    }

    @Schema(name = "AccountDeletionScheduled")
    public record Scheduled(Instant executesAt, int graceDays) {
    }
}
