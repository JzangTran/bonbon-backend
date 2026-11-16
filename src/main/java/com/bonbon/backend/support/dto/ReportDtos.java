package com.bonbon.backend.support.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Content reports (flows/reporting/report-content.md, review-reports.md). */
public final class ReportDtos {

    private ReportDtos() {
    }

    public static final String TARGETS = "SHOP|MESSAGE|REVIEW|REVIEW_RESPONSE";
    public static final String REASONS = "SPAM|OFFENSIVE|SCAM_OR_FRAUD|INAPPROPRIATE_IMAGE|FALSE_INFORMATION|OTHER";

    @Schema(name = "ReportRequest", description = "Báo cáo một cửa hàng, một tin nhắn, một đánh giá hoặc phản hồi của quán.")
    public record Create(
            @Schema(description = "`SHOP`, `MESSAGE`, `REVIEW` hoặc `REVIEW_RESPONSE`.") @NotNull @Pattern(regexp = TARGETS, message = "Loại nội dung không hợp lệ.") String targetType,
            @NotNull UUID targetId,
            @Schema(description = "`SPAM`, `OFFENSIVE` (quấy rối, thù ghét, đe doạ), `SCAM_OR_FRAUD` (lừa đảo, đòi trả tiền ngoài ứng dụng), `INAPPROPRIATE_IMAGE`, `FALSE_INFORMATION`, `OTHER` (cần ghi chú).")
            @NotNull @Pattern(regexp = REASONS, message = "Lý do không hợp lệ.") String reasonCode,
            @Schema(description = "Tối đa 500 ký tự; bắt buộc khi lý do là `OTHER`.") @Size(max = 500) String note) {
    }

    @Schema(name = "ReportReceipt", description = "Báo cáo đã nhận. Nội dung bị báo cáo vẫn hiện cho tới khi quản trị viên quyết định.")
    public record Receipt(UUID id, String status, Instant createdAt,
            @Schema(description = "true nếu người gọi đã có báo cáo đang mở cho nội dung này; khi đó trả về báo cáo cũ, không tạo thêm.") boolean alreadyReported) {
    }

    @Schema(name = "ReportContestRequest", description = "Người bị xử lý khiếu nại quyết định, một lần, trong 7 ngày.")
    public record Contest(@NotBlank @Size(max = 500) String note) {
    }

    @Schema(name = "ReportDecisionRequest")
    public record Decide(
            @Schema(description = "`DISMISS` (không vi phạm), `DUPLICATE` (trùng báo cáo đã xử lý), `HIDE` (gỡ tin nhắn, ẩn đánh giá hoặc phản hồi), `RESTRICT` (gỡ tin và hạn chế người gửi nhắn tin `restrictDays` ngày, chỉ với tin nhắn), `ESCALATE` (chuyển sang đình chỉ hoặc điểm phạt; báo cáo không tự đình chỉ quán).")
            @NotNull @Pattern(regexp = "DISMISS|DUPLICATE|HIDE|RESTRICT|ESCALATE", message = "Hành động không hợp lệ.") String action,
            @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Số ngày hạn chế nhắn tin, 1–30; chỉ dùng với `RESTRICT`.") @Min(1) @Max(30) Integer restrictDays) {
    }

    @Schema(name = "ReportRow", description = "Một báo cáo trong hàng đợi.")
    public record Row(UUID id, String targetType, UUID targetId, String targetLabel, String reasonCode, String status, String decisionCode,
            @Schema(description = "Đã quá 24 giờ mà chưa quyết định.") boolean overdue,
            @Schema(description = "Người bị xử lý đã khiếu nại quyết định trước.") boolean contested,
            Instant createdAt) {
    }

    @Schema(name = "ReportPage")
    public record Page(List<Row> items, int page, int size, long total) {
    }

    @Schema(name = "ReportDetail")
    public record Detail(
            UUID id, String targetType, UUID targetId, String targetLabel, UUID targetVendorId, String reasonCode, String note,
            @Schema(description = "`CUSTOMER` hoặc `SELLER`.") String reporterSide,
            @Schema(description = "Báo cáo của người báo cáo trong 90 ngày: tổng số và số bị bác.") long reporterReports90d, long reporterDismissed90d,
            @Schema(description = "Số báo cáo trước đây về cùng người hoặc quán đã bị xử lý.") long targetActionedBefore,
            @Schema(description = "Nội dung tại lúc báo cáo. Tin nhắn có 10 tin trước và sau; ảnh là liên kết ngắn hạn.") Map<String, Object> evidence,
            String status, String decisionCode, String decisionReason, Instant decidedAt, Instant contestedAt, String contestNote, Instant createdAt,
            @Schema(description = "Hành động quản trị viên này được chọn cho loại nội dung và quyền đang có.") List<String> allowedActions) {
    }
}
