package com.bonbon.backend.shopperformance.dto;

import java.util.List;

import com.bonbon.backend.shopperformance.dto.CaseRequests.Line;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminCaseRequests {

    private AdminCaseRequests() {
    }

    @Schema(name = "DecideOrderCaseRequest")
    public record Decide(
            @Schema(description = "UPHELD (khách được hoàn tiền, quán chịu) hoặc DISMISSED (không có gì thay đổi về tiền).")
            @NotNull @Pattern(regexp = "UPHELD|DISMISSED", message = "Kết quả phải là UPHELD hoặc DISMISSED.") String outcome,
            @Schema(description = "Lý do; cả khách và quán đều thấy.") @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Chấp nhận ít hơn đã báo: chỉ các dòng này, mỗi dòng số phần từ 1 đến số khách báo; tiền tính lại theo tỷ lệ từ số đã lưu. Chỉ với UPHELD và các loại thiếu món, sai món, chất lượng.")
            @Size(max = 50) List<@Valid Line> lines) {
    }

    @Schema(name = "ReopenOrderCaseRequest")
    public record Reopen(@Schema(description = "Lý do mở lại, được ghi lại.") @NotBlank @Size(max = 500) String reason) {
    }
}
