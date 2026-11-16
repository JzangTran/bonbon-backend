package com.bonbon.backend.identity.legal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Data-subject requests (flows/legal/data-subject-requests.md). */
public final class DataRequestDtos {

    private DataRequestDtos() {
    }

    @Schema(name = "DataRequestCreate", description = "Yêu cầu cần người xử lý. Tải dữ liệu dùng `POST /api/me/data-export`, xoá tài khoản dùng luồng xoá tài khoản.")
    public record Create(
            @Schema(description = "`CORRECT` (sửa dữ liệu không tự sửa được), `WITHDRAW` (rút lại đồng ý), `RESTRICT` (hạn chế xử lý), `OBJECT` (phản đối xử lý).")
            @NotNull @Pattern(regexp = "CORRECT|WITHDRAW|RESTRICT|OBJECT", message = "Loại yêu cầu không hợp lệ.") String type,
            @Schema(description = "Mô tả yêu cầu, 10–2000 ký tự.") @NotBlank @Size(min = 10, max = 2000) String details) {
    }

    @Schema(name = "DataRequest")
    public record View(UUID id, String type, String status, String details, Instant receivedAt,
            @Schema(description = "Hạn phản hồi đã nhận.") Instant ackDueAt,
            @Schema(description = "Hạn hoàn thành.") Instant dueAt,
            boolean extended, String extensionReason, String responseNote, Instant closedAt,
            @Schema(description = "Liên kết tải tệp dữ liệu (15 phút), chỉ với yêu cầu tải dữ liệu đã xong trong 7 ngày.") String downloadUrl) {
    }

    @Schema(name = "AdminDataRequest")
    public record AdminView(UUID id, UUID userId, String email, String name, String audience, String type, String status, String details,
            Instant receivedAt, Instant ackDueAt, Instant dueAt, boolean overdue, boolean extended, String extensionReason, String responseNote,
            Instant closedAt) {
    }

    @Schema(name = "AdminDataRequestPage")
    public record Page(List<AdminView> items, int page, int size, long total) {
    }

    @Schema(name = "DataRequestHandle", description = "Bắt đầu xử lý, hoàn thành, từ chối (cần lý do) hoặc gia hạn một lần (cần lý do).")
    public record Handle(
            @NotNull @Pattern(regexp = "START|COMPLETE|REJECT|EXTEND", message = "Hành động không hợp lệ.") String action,
            @Schema(description = "Trả lời cho người yêu cầu, hoặc lý do gia hạn.") @Size(max = 2000) String note,
            @Schema(description = "Số ngày gia hạn, 1–30.") @Min(1) @Max(30) Integer extendDays) {
    }
}
