package com.bonbon.backend.shopperformance.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CaseRequests {

    private CaseRequests() {
    }

    @Schema(name = "ReportNotReceivedRequest")
    public record NotReceived(@Schema(description = "Ghi chú thêm (không bắt buộc).") @Size(max = 500) String note) {
    }

    @Schema(name = "CaseLineRequest", description = "Một dòng món bị ảnh hưởng và số phần bị ảnh hưởng.")
    public record Line(
            @Schema(description = "Id dòng món của đơn (`lines[].id` trong chi tiết đơn).") @NotNull UUID orderItemId,
            @Schema(description = "Số phần bị ảnh hưởng, từ 1 đến số đã đặt.") @Min(1) int quantity) {
    }

    @Schema(name = "ReportIncidentRequest")
    public record Incident(
            @Schema(description = "MISSING_ITEM (thiếu món), WRONG_ITEM (sai món) hoặc QUALITY (chất lượng).")
            @NotNull @Pattern(regexp = "MISSING_ITEM|WRONG_ITEM|QUALITY", message = "Loại phải là MISSING_ITEM, WRONG_ITEM hoặc QUALITY.") String type,
            @NotEmpty @Size(max = 50) List<@Valid Line> lines,
            @Schema(description = "Khoá ảnh đã tải lên qua `POST /api/orders/{id}/case-photos`; tối đa 3. Bắt buộc ít nhất một với WRONG_ITEM và QUALITY.")
            @Size(max = 10) List<String> photoKeys,
            @Schema(description = "Ghi chú thêm (không bắt buộc).") @Size(max = 500) String note) {
    }

    @Schema(name = "DisputeOrderCaseRequest")
    public record Dispute(@Schema(description = "Lý do quán không đồng ý; quản trị viên và khách đều thấy.") @jakarta.validation.constraints.NotBlank @Size(max = 500) String note) {
    }

    @Schema(name = "QuoteIncidentRequest")
    public record Quote(@NotEmpty @Size(max = 50) List<@Valid Line> lines) {
    }
}
