package com.bonbon.backend.shopperformance.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.shopperformance.dto.AdminCaseRequests;
import com.bonbon.backend.shopperformance.dto.AdminCaseViews;
import com.bonbon.backend.shopperformance.dto.CaseViews;
import com.bonbon.backend.shopperformance.service.AdminCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_ORDER_CASES, description = "Hàng đợi khiếu nại về đơn đã giao mà quán phản đối hoặc không trả lời. Quản trị viên xem bằng chứng và quyết chấp nhận (khách được hoàn tiền, quán chịu) hoặc bác bỏ, kèm lý do cả hai bên đều thấy. Mỗi khiếu nại được quyết một lần.")
@RestController
@RequestMapping("/api/admin/order-cases")
@Validated
class AdminOrderCaseController {

    private final AdminCaseService cases;

    AdminOrderCaseController(AdminCaseService cases) {
        this.cases = cases;
    }

    @Operation(operationId = "listOrderCases", summary = "Hàng đợi khiếu nại", description = "Mặc định là hàng đợi cần quyết (`OPEN`), cũ nhất trước; các trạng thái khác mới nhất trước. `type` lọc theo loại. `shopBears` là số quán phải chịu nếu được chấp nhận.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải AWAITING_SHOP, AWAITING_CUSTOMER, OPEN, UPHELD hoặc DISMISSED.")
    @ApiError(status = 400, code = "INVALID_TYPE", when = "`type` không phải một loại khiếu nại.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–100.")
    @GetMapping
    @PreAuthorize("hasAuthority('order-case:read')")
    AdminCaseViews.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return cases.list(status, type, page, size);
    }

    @Operation(operationId = "getOrderCaseDetail", summary = "Chi tiết một khiếu nại", description = "Các dòng bị báo và số tiền hoàn, ảnh (liên kết ngắn hạn), ghi chú của khách, câu trả lời của quán hoặc dấu hiệu không trả lời, lịch sử khiếu nại của khách và của quán (số bị bác bỏ trong 90 ngày gần nhất), và nhật ký các bước đã xảy ra.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại với id này.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order-case:read')")
    AdminCaseViews.Detail detail(@PathVariable UUID id) {
        return cases.detail(id);
    }

    @Operation(operationId = "decideOrderCase", summary = "Quyết một khiếu nại", description = "`UPHELD`: khách được hoàn tiền (MoMo cho đơn online, chuyển khoản cho đơn tiền mặt), sổ cái của quán ghi tiền hoàn và hoàn lại hoa hồng phần đó; có thể chỉ chấp nhận một số dòng hoặc số phần (`lines`), tiền tính lại theo tỷ lệ từ số đã lưu. `DISMISSED`: tiền không đổi. Cả hai trường hợp nhả phần tiền đang giữ và cả hai bên được báo kèm lý do. Hai quản trị viên không quyết được cùng một khiếu nại.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại với id này.")
    @ApiError(status = 409, code = "CASE_NOT_OPEN", when = "Khiếu nại không ở hàng đợi quản trị (còn chờ quán, hoặc đã được quyết); kèm `status`.")
    @ApiError(status = 400, code = "LINES_NOT_ALLOWED", when = "`lines` chỉ dùng với UPHELD cho khiếu nại thiếu món, sai món hoặc chất lượng chưa từng mở lại.")
    @ApiError(status = 400, code = "LINE_NOT_IN_CASE", when = "Có món không thuộc khiếu nại này.")
    @ApiError(status = 400, code = "LINE_REPEATED", when = "Một món được chọn nhiều lần.")
    @ApiError(status = 400, code = "QUANTITY_INVALID", when = "Số phần ngoài khoảng từ 1 đến số khách đã báo.")
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAuthority('order-case:decide')")
    CaseViews.Case decide(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AdminCaseRequests.Decide request) {
        return cases.decide(principal, id, request);
    }

    @Operation(operationId = "reopenOrderCase", summary = "Mở lại khiếu nại đã quyết", description = "Đưa khiếu nại đã quyết về hàng đợi để quyết lại, một lần duy nhất, với lý do được ghi lại. Bút toán đã ghi không bị sửa: nếu khiếu nại đã được chấp nhận rồi bị bác bỏ ở lần quyết lại, sổ cái ghi một điều chỉnh ngược để trả lại cho quán (khoản đã hoàn cho khách thì không thu hồi được). Cả hai bên được báo.")
    @ApiError(status = 404, code = "CASE_NOT_FOUND", when = "Không có khiếu nại với id này.")
    @ApiError(status = 409, code = "CASE_NOT_DECIDED", when = "Khiếu nại chưa có quyết định để xem lại; kèm `status`.")
    @ApiError(status = 409, code = "CASE_ALREADY_REOPENED", when = "Khiếu nại đã được mở lại một lần.")
    @PostMapping("/{id}/reopen")
    @PreAuthorize("hasAuthority('order-case:decide')")
    CaseViews.Case reopen(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody AdminCaseRequests.Reopen request) {
        return cases.reopen(principal, id, request.reason());
    }
}
