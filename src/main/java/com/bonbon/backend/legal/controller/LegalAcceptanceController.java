package com.bonbon.backend.legal.controller;

import java.util.HashSet;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.legal.dto.LegalAdminRequests;
import com.bonbon.backend.legal.dto.LegalAdminViews;
import com.bonbon.backend.legal.service.LegalAcceptanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.LEGAL)
@RestController
@RequestMapping("/api/legal")
@PreAuthorize("hasAnyAuthority('order:create', 'vendor:create')")
class LegalAcceptanceController {

    private final LegalAcceptanceService acceptance;

    LegalAcceptanceController(LegalAcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @Operation(operationId = "getPendingLegalDocuments", summary = "Văn bản cần đồng ý lại", description = "Khi một thay đổi quan trọng của điều khoản hoặc chính sách bắt đầu có hiệu lực, người dùng đã đồng ý bản cũ phải đồng ý bản mới trước khi tiếp tục: mọi lệnh gọi khác trả 403 `LEGAL_ACCEPTANCE_REQUIRED` kèm danh sách này. Rỗng khi không có gì chờ. Nội dung đầy đủ lấy ở `GET /api/legal/documents/{type}`.")
    @ApiError(status = 403, code = "FORBIDDEN", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @GetMapping("/pending")
    LegalAdminViews.PendingList pending(CurrentPrincipal principal) {
        return new LegalAdminViews.PendingList(acceptance.pending(principal.id(), principal.activeRole()));
    }

    @Operation(operationId = "acceptLegalDocuments", summary = "Đồng ý văn bản đã cập nhật", description = "Gửi đúng id các văn bản đang chờ (`GET /api/legal/pending`). Lưu bằng chứng đồng ý kèm kênh, phiên bản app và IP như lúc đăng ký. Không có gì chờ thì không làm gì.")
    @ApiError(status = 400, code = "CONSENT_REQUIRED", when = "Thiếu một văn bản đang chờ.")
    @ApiError(status = 409, code = "LEGAL_DOCUMENTS_CHANGED", when = "Có id không còn là phiên bản đang chờ (văn bản vừa đổi); tải lại danh sách.")
    @ApiResponse(responseCode = "204", description = "Đã ghi nhận.")
    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(CurrentPrincipal principal, @Valid @RequestBody LegalAdminRequests.Accept request, HttpServletRequest http) {
        acceptance.accept(principal.id(), principal.activeRole(), new HashSet<>(request.documentIds()), ClientContext.from(http));
    }
}
