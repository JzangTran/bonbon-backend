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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.REPORTS, description = "Khách và người bán báo cáo cửa hàng, tin nhắn, đánh giá hoặc phản hồi của quán. Báo cáo là yêu cầu xem xét, không tự ẩn hay phạt ai; "
        + "nội dung vẫn hiện cho tới khi quản trị viên quyết định. Người báo cáo được báo kết quả, người bị báo cáo không biết ai báo.")
@RestController
@RequestMapping("/api/reports")
@PreAuthorize("isAuthenticated()")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(operationId = "fileReport", summary = "Gửi báo cáo vi phạm",
            description = "Khách báo cáo được cửa hàng, tin nhắn của quán, đánh giá và phản hồi của quán. Người bán báo cáo được tin nhắn của khách và đánh giá về quán mình. "
                    + "Nội dung được chụp lại lúc báo cáo (tin nhắn kèm 10 tin trước và sau). Mỗi người một báo cáo đang mở cho mỗi nội dung; gửi lại trả về báo cáo cũ. Tối đa 10 báo cáo một ngày.")
    @ApiError(status = 400, code = "OWN_CONTENT", when = "Nội dung của chính người gọi.")
    @ApiError(status = 400, code = "NOTE_REQUIRED", when = "Chọn lý do Khác mà không ghi chú.")
    @ApiError(status = 403, code = "NOT_ALLOWED", when = "Vai trò này không báo cáo được loại nội dung này.")
    @ApiError(status = 404, code = "REPORT_TARGET_NOT_FOUND", when = "Không có nội dung này hoặc người gọi không thấy nó.")
    @ApiError(status = 429, code = "TOO_MANY_REPORTS", when = "Quá 10 báo cáo trong một ngày.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReportDtos.Receipt file(CurrentPrincipal principal, @Valid @RequestBody ReportDtos.Create request) {
        return reports.file(principal, request);
    }

    @Operation(operationId = "contestReport", summary = "Khiếu nại quyết định xử lý nội dung của mình",
            description = "Người có nội dung bị gỡ, bị ẩn hoặc bị hạn chế nhắn tin khiếu nại một lần trong 7 ngày. Báo cáo mở lại để quản trị viên xem lần hai; trong lúc chờ, quyết định cũ vẫn giữ.")
    @ApiError(status = 404, code = "REPORT_NOT_FOUND", when = "Không có báo cáo này về nội dung của người gọi.")
    @ApiError(status = 409, code = "ALREADY_CONTESTED", when = "Đã khiếu nại rồi.")
    @ApiError(status = 409, code = "CONTEST_WINDOW_CLOSED", when = "Quá 7 ngày.")
    @ApiError(status = 409, code = "NOT_CONTESTABLE", when = "Quyết định không xử lý gì.")
    @PostMapping("/{id}/contest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void contest(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ReportDtos.Contest request) {
        reports.contest(principal, id, request);
    }
}
