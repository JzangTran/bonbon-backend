package com.bonbon.backend.merchantapproval.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchantapproval.dto.SuspensionDtos;
import com.bonbon.backend.merchantapproval.service.SuspensionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_SHOP_REVIEW, description = "Duyệt hồ sơ mở cửa hàng và đặt bán kính giao tối đa của nền tảng.")
@RestController
@RequestMapping("/api/admin/merchants/{id}")
@Validated
class SuspensionController {

    private final SuspensionService suspensions;

    SuspensionController(SuspensionService suspensions) {
        this.suspensions = suspensions;
    }

    @Operation(operationId = "suspendShop", summary = "Đình chỉ cửa hàng", description = "Cần lý do. Mặc định đình chỉ có lịch, hiệu lực sau đúng 5 ngày (`legal.seller_restriction_notice_days`, mức tối thiểu theo luật): quán được báo ngay kèm lý do và ngày, vẫn hoạt động trong thời gian báo trước, rồi hệ thống tự đình chỉ khi tới hạn. Chỉ theo yêu cầu của cơ quan có thẩm quyền mới đình chỉ ngay (`immediate` kèm `authorityReference`). Quán bị đình chỉ vẫn đăng nhập, hoàn tất đơn đang làm và xem thu nhập, nhưng khách không tìm thấy và không đặt thêm được; menu và hồ sơ không sửa được.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có cửa hàng với id này.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Cửa hàng không ở trạng thái đang hoạt động; kèm `status`.")
    @ApiError(status = 409, code = "SUSPENSION_ALREADY_OPEN", when = "Cửa hàng đã có lịch đình chỉ hoặc đang bị đình chỉ.")
    @ApiError(status = 400, code = "NOTICE_TOO_SHORT", when = "`effectiveAt` cách bây giờ chưa đủ 5 ngày; kèm `earliest`.")
    @ApiError(status = 400, code = "AUTHORITY_REFERENCE_REQUIRED", when = "Đình chỉ ngay thiếu số hiệu và ngày yêu cầu của cơ quan có thẩm quyền.")
    @ApiError(status = 400, code = "EFFECTIVE_AT_NOT_ALLOWED", when = "Đình chỉ ngay không có `effectiveAt`.")
    @ApiResponse(responseCode = "201", description = "Đã lập đình chỉ.")
    @PostMapping("/suspend")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('merchant-approval:suspend')")
    SuspensionDtos.Suspension suspend(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody SuspensionDtos.Suspend request) {
        return suspensions.suspend(principal, id, request);
    }

    @Operation(operationId = "cancelShopSuspension", summary = "Huỷ lịch đình chỉ", description = "Trong thời gian báo trước, với lý do được ghi lại (ví dụ quán đã trả nợ hoặc tranh chấp đã xong); quán được báo.")
    @ApiError(status = 409, code = "NO_SCHEDULED_SUSPENSION", when = "Cửa hàng không có lịch đình chỉ nào đang chờ.")
    @PostMapping("/suspension/cancel")
    @PreAuthorize("hasAuthority('merchant-approval:suspend')")
    SuspensionDtos.Suspension cancel(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody SuspensionDtos.End request) {
        return suspensions.cancel(principal, id, request.reason());
    }

    @Operation(operationId = "reinstateShop", summary = "Khôi phục cửa hàng bị đình chỉ", description = "Đưa cửa hàng từ đình chỉ về hoạt động ngay, với lý do được ghi lại; quán được báo và hiện lại với khách.")
    @ApiError(status = 409, code = "SHOP_NOT_SUSPENDED", when = "Cửa hàng không bị đình chỉ.")
    @PostMapping("/reinstate")
    @PreAuthorize("hasAuthority('merchant-approval:suspend')")
    SuspensionDtos.Suspension reinstate(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody SuspensionDtos.End request) {
        return suspensions.reinstate(principal, id, request.reason());
    }

    @Operation(operationId = "listShopSuspensions", summary = "Lịch sử đình chỉ của một cửa hàng", description = "Mọi lần đình chỉ, mới nhất trước: loại, trạng thái, lý do, ngày báo, ngày hiệu lực và lý do kết thúc.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có cửa hàng với id này.")
    @GetMapping("/suspensions")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    SuspensionDtos.History history(@PathVariable UUID id) {
        return suspensions.history(id);
    }
}
