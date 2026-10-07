package com.bonbon.backend.settlement.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.settlement.dto.CommissionRequests;
import com.bonbon.backend.settlement.dto.CommissionViews;
import com.bonbon.backend.settlement.service.CommissionRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_COMMISSION, description = "Tỷ lệ hoa hồng của nền tảng: mặc định, theo ngành (kế thừa xuống ngành con) và lịch sử thay đổi. Tỷ lệ đã gồm VAT; đơn đã đặt giữ nguyên tỷ lệ lúc đặt.")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('commission:write')")
class AdminCommissionController {

    private final CommissionRateService rates;

    AdminCommissionController(CommissionRateService rates) {
        this.rates = rates;
    }

    @Operation(operationId = "getCommissionRates", summary = "Tỷ lệ hoa hồng đang áp dụng", description = "Tỷ lệ mặc định, mức VAT nằm trong hoa hồng và từng ngành (cha trước con) với tỷ lệ riêng, tỷ lệ thực tế và nguồn của nó: `OWN`, `ANCESTOR` (kế thừa từ ngành cha, kèm tên) hoặc `DEFAULT`.")
    @GetMapping("/commission-rates")
    CommissionViews.Rates get() {
        return rates.rates();
    }

    @Operation(operationId = "getCommissionHistory", summary = "Lịch sử đổi tỷ lệ hoa hồng", description = "Mới nhất trước: ai đổi, từ bao nhiêu sang bao nhiêu, từ lúc nào. Lọc theo `scope` (`DEFAULT` hoặc `CATEGORY`) hoặc `categoryId`. Dòng cũ không bao giờ bị sửa hay xoá.")
    @ApiError(status = 400, code = "INVALID_SCOPE", when = "`scope` không phải DEFAULT hoặc CATEGORY.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping("/commission-rates/history")
    CommissionViews.History history(@RequestParam(required = false) String scope, @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return rates.history(scope, categoryId, page, size);
    }

    @Operation(operationId = "setDefaultCommissionRate", summary = "Đặt tỷ lệ hoa hồng mặc định", description = "Áp dụng cho đơn đặt sau thời điểm này, cho mọi món có ngành (và ngành cha của nó) không có tỷ lệ riêng. Đơn đã đặt giữ tỷ lệ cũ. Từ 0 đến 30, tối đa hai chữ số thập phân. Đặt lại đúng tỷ lệ hiện tại không tạo dòng lịch sử.")
    @PutMapping("/settings/commission-rate")
    CommissionViews.Rates setDefault(CurrentPrincipal principal, @Valid @RequestBody CommissionRequests.Rate request) {
        return rates.setDefault(principal, request.ratePercent());
    }

    @Operation(operationId = "setCategoryCommissionRate", summary = "Đặt tỷ lệ hoa hồng cho một ngành", description = "Ngành con chưa có tỷ lệ riêng sẽ kế thừa tỷ lệ này. Từ 0 đến 30. Đổi bằng màn sửa ngành cũng ghi vào cùng lịch sử.")
    @ApiError(status = 404, code = "CATEGORY_NOT_FOUND", when = "Ngành không tồn tại.")
    @PutMapping("/categories/{id}/commission-rate")
    CommissionViews.Rates setCategory(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody CommissionRequests.Rate request) {
        return rates.setCategory(principal, id, request.ratePercent());
    }

    @Operation(operationId = "clearCategoryCommissionRate", summary = "Bỏ tỷ lệ riêng của một ngành", description = "Ngành quay lại kế thừa từ ngành cha gần nhất có tỷ lệ, rồi đến tỷ lệ mặc định.")
    @ApiError(status = 404, code = "CATEGORY_NOT_FOUND", when = "Ngành không tồn tại.")
    @DeleteMapping("/categories/{id}/commission-rate")
    CommissionViews.Rates clearCategory(CurrentPrincipal principal, @PathVariable UUID id) {
        return rates.setCategory(principal, id, null);
    }
}
