package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.ReportDtos;
import com.bonbon.backend.support.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_REPORTS, description = "Hàng đợi báo cáo vi phạm: xem bằng chứng, lịch sử của người báo cáo và người bị báo cáo, rồi quyết định kèm lý do.")
@RestController
@RequestMapping("/api/admin/reports")
class AdminReportController {

    private final ReportService reports;

    AdminReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(operationId = "listReports", summary = "Hàng đợi báo cáo",
            description = "Mặc định các báo cáo đang mở, báo cáo được khiếu nại lại lên trước, rồi cũ nhất trước. `overdue` là đã quá 24 giờ chưa quyết định.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải OPEN, ACTIONED, DISMISSED hoặc ALL.")
    @GetMapping
    @PreAuthorize("hasAuthority('report:read')")
    ReportDtos.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String targetType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reports.queue(status, targetType, page, size);
    }

    @Operation(operationId = "getReport", summary = "Chi tiết một báo cáo", description = "Gồm bằng chứng lúc báo cáo và các hành động người gọi được chọn.")
    @ApiError(status = 404, code = "REPORT_NOT_FOUND", when = "Không có báo cáo này.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('report:read')")
    ReportDtos.Detail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return reports.detail(principal, id);
    }

    @Operation(operationId = "decideReport", summary = "Quyết định một báo cáo",
            description = "Áp dụng cho mọi báo cáo đang mở về cùng nội dung; mỗi người báo cáo được báo kết quả, người bị xử lý được báo lý do và hạn khiếu nại. "
                    + "Ẩn đánh giá cần thêm quyền `review:moderate`, hạn chế nhắn tin cần `moderation:write`.")
    @ApiError(status = 403, code = "ACTION_NOT_ALLOWED", when = "Hành động không áp dụng cho loại nội dung hoặc thiếu quyền.")
    @ApiError(status = 409, code = "REPORT_DECIDED", when = "Báo cáo đã được quyết định.")
    @ApiError(status = 400, code = "RESTRICT_DAYS_REQUIRED", when = "Hạn chế mà không có số ngày.")
    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAuthority('report:decide')")
    ReportDtos.Detail decide(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ReportDtos.Decide request) {
        return reports.decide(principal, id, request);
    }
}
