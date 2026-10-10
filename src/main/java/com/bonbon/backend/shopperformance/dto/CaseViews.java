package com.bonbon.backend.shopperformance.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public final class CaseViews {

    private CaseViews() {
    }

    @Schema(name = "OrderCaseLine")
    public record Line(
            UUID orderItemId,
            String name,
            @Schema(description = "Số phần bị ảnh hưởng.") int quantity,
            @Schema(description = "Tiền hoàn cho dòng này: số khách thực trả (đã trừ phần giảm giá, gồm lựa chọn thêm) theo tỷ lệ số phần.") int refundAmount) {
    }

    @Schema(name = "OrderCasePhoto")
    public record Photo(String key, @Schema(description = "Liên kết xem ảnh, hết hạn sau ít phút.") String url) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "OrderCase", description = "Một khiếu nại về đơn đã giao.")
    public record Case(
            UUID id,
            UUID orderId,
            @Schema(description = "Mã đơn hiển thị.") long orderNumber,
            @Schema(description = "NOT_RECEIVED, MISSING_ITEM, WRONG_ITEM, QUALITY hoặc CUSTOMER_NO_SHOW.") String type,
            @Schema(description = "AWAITING_SHOP (chờ quán trả lời), AWAITING_CUSTOMER, OPEN (chờ quản trị quyết), UPHELD (được chấp nhận) hoặc DISMISSED (bị bác bỏ).") String status,
            @Schema(description = "Số tiền khách sẽ được hoàn nếu khiếu nại được chấp nhận.") int refundAmount,
            String note,
            @Schema(description = "Hạn quán trả lời; quá hạn thì khiếu nại chuyển cho quản trị.") Instant shopResponseDueAt,
            @Schema(description = "ACCEPTED hoặc DISPUTED, khi quán đã trả lời.") String shopResponse,
            String shopResponseNote,
            Instant openedAt,
            Instant decidedAt,
            @Schema(description = "Ai quyết: SHOP (quán chấp nhận), ADMIN, SYSTEM hoặc CUSTOMER.") String decidedBy,
            @Schema(description = "Lý do của quyết định, cả hai bên đều thấy.") String reason,
            List<Line> lines,
            List<Photo> photos,
            @Schema(description = "Tên người nhận ghi trên đơn. Chỉ quán và quản trị viên thấy.") String customerName,
            @Schema(description = "Số tiền quán phải chịu nếu khiếu nại được chấp nhận: tiền hoàn trừ hoa hồng nền tảng hoàn lại. Chỉ quán và quản trị viên thấy.") Integer shopBears,
            @Schema(description = "Báo cáo khách vắng mặt: câu trả lời của khách (UNABLE, RECEIVED hoặc SHOP_NEVER_CAME).") String customerAnswer,
            String customerAnswerNote,
            @Schema(description = "Hạn khách trả lời; quá hạn thì chuyển cho quản trị viên.") Instant customerAnswerDueAt,
            @Schema(description = "Kết cục của báo cáo khách vắng mặt: CUSTOMER_AT_FAULT, CUSTOMER_RECEIVED hoặc SHOP_NEVER_CAME.") String noShowOutcome) {
    }

    @Schema(name = "OrderCaseSummary")
    public record Summary(
            UUID id,
            UUID orderId,
            long orderNumber,
            String type,
            String status,
            int refundAmount,
            @Schema(description = "Số tiền quán phải chịu nếu được chấp nhận.") int shopBears,
            Instant shopResponseDueAt,
            Instant openedAt) {
    }

    @Schema(name = "OrderCasePage")
    public record Page(List<Summary> items, int page, int size, long total) {
    }

    @Schema(name = "OrderCaseQuote", description = "Số tiền hoàn tính trước khi gửi khiếu nại.")
    public record Quote(int refundAmount, List<Line> lines) {
    }

    @Schema(name = "OrderCasePhotoUpload")
    public record Upload(@Schema(description = "Gửi khoá này trong `photoKeys` khi báo vấn đề.") String photoKey, @Schema(description = "Liên kết xem ảnh vừa tải lên.") String url) {
    }
}
