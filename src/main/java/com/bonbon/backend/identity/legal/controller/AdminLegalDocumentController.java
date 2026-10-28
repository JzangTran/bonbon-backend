package com.bonbon.backend.identity.legal.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.identity.legal.dto.LegalAdminRequests;
import com.bonbon.backend.identity.legal.dto.LegalAdminViews;
import com.bonbon.backend.identity.legal.service.LegalAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_LEGAL, description = "Đăng phiên bản mới của điều khoản và chính sách: soạn bản nháp, đăng với ngày hiệu lực, hoặc huỷ trước ngày hiệu lực. Văn bản đã đăng không bao giờ sửa (nội dung được băm); sửa lỗi là tạo phiên bản mới. Thay đổi quan trọng phải báo trước ít nhất 15 ngày; người dùng được báo lúc đăng và nhắc lại 3 ngày trước ngày hiệu lực, rồi phải đồng ý lại để tiếp tục dùng.")
@RestController
@RequestMapping("/api/admin/legal-documents")
@PreAuthorize("hasAuthority('legal-document:write')")
@Validated
class AdminLegalDocumentController {

    private final LegalAdminService legal;

    AdminLegalDocumentController(LegalAdminService legal) {
        this.legal = legal;
    }

    @Operation(operationId = "listLegalDocumentVersions", summary = "Các phiên bản văn bản", description = "Mọi phiên bản theo loại, mới nhất trước, kèm trạng thái hiển thị (DRAFT, SCHEDULED, EFFECTIVE, SUPERSEDED, CANCELLED), số tài khoản đã đồng ý và số còn chưa đồng ý.")
    @GetMapping
    LegalAdminViews.Versions list() {
        return legal.list();
    }

    @Operation(operationId = "getLegalDocumentVersion", summary = "Xem một phiên bản", description = "Kèm toàn bộ nội dung.")
    @ApiError(status = 404, code = "LEGAL_DOCUMENT_NOT_FOUND", when = "Không có phiên bản này.")
    @GetMapping("/{id}")
    LegalAdminViews.Detail get(@PathVariable UUID id) {
        return legal.get(id);
    }

    @Operation(operationId = "createLegalDraft", summary = "Tạo bản nháp", description = "Phiên bản kế tiếp của một loại văn bản, chỉ quản trị viên thấy. Mỗi loại một bản nháp cùng lúc.")
    @ApiError(status = 409, code = "DRAFT_EXISTS", when = "Loại này đang có một bản nháp.")
    @ApiResponse(responseCode = "201", description = "Đã tạo.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    LegalAdminViews.Detail create(CurrentPrincipal principal, @Valid @RequestBody LegalAdminRequests.CreateDraft request) {
        return legal.createDraft(principal, request);
    }

    @Operation(operationId = "updateLegalDraft", summary = "Sửa bản nháp", description = "Chỉ các trường được gửi mới đổi.")
    @ApiError(status = 404, code = "LEGAL_DOCUMENT_NOT_FOUND", when = "Không có phiên bản này.")
    @ApiError(status = 409, code = "NOT_A_DRAFT", when = "Phiên bản đã đăng hoặc đã huỷ.")
    @PatchMapping("/{id}")
    LegalAdminViews.Detail update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody LegalAdminRequests.UpdateDraft request) {
        return legal.updateDraft(principal, id, request);
    }

    @Operation(operationId = "publishLegalDocument", summary = "Đăng phiên bản", description = "Từ lúc này nội dung bất biến. Thay đổi quan trọng: ngày hiệu lực cách bây giờ ít nhất `legal.min_notice_days` (15) ngày, bỏ trống là đúng số ngày đó; thay đổi nhỏ: có hiệu lực ngay nếu bỏ trống. Phiên bản trước hết hiệu lực khi phiên bản này bắt đầu có hiệu lực. Mọi người thuộc đối tượng được báo trong ứng dụng và qua email.")
    @ApiError(status = 404, code = "LEGAL_DOCUMENT_NOT_FOUND", when = "Không có phiên bản này.")
    @ApiError(status = 409, code = "NOT_A_DRAFT", when = "Phiên bản không còn là bản nháp.")
    @ApiError(status = 409, code = "VERSION_ALREADY_SCHEDULED", when = "Loại này đã có một phiên bản chờ đến ngày hiệu lực.")
    @ApiError(status = 400, code = "EFFECTIVE_TOO_SOON", when = "Thay đổi quan trọng nhưng ngày hiệu lực chưa đủ thời gian báo trước.")
    @ApiError(status = 400, code = "EFFECTIVE_IN_THE_PAST", when = "Ngày hiệu lực ở quá khứ.")
    @PostMapping("/{id}/publish")
    LegalAdminViews.Detail publish(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody(required = false) LegalAdminRequests.Publish request) {
        return legal.publish(principal, id, request);
    }

    @Operation(operationId = "cancelLegalDocument", summary = "Huỷ phiên bản", description = "Bản nháp được bỏ. Bản đã đăng nhưng chưa đến ngày hiệu lực được rút lại với lý do bắt buộc và mọi người được báo là thay đổi sẽ không áp dụng. Bản đã có hiệu lực không huỷ được, chỉ thay bằng phiên bản mới hơn.")
    @ApiError(status = 404, code = "LEGAL_DOCUMENT_NOT_FOUND", when = "Không có phiên bản này.")
    @ApiError(status = 409, code = "ALREADY_EFFECTIVE", when = "Phiên bản đã có hiệu lực.")
    @ApiError(status = 409, code = "ALREADY_CANCELLED", when = "Phiên bản đã bị huỷ.")
    @ApiError(status = 400, code = "REASON_REQUIRED", when = "Huỷ bản đã đăng nhưng thiếu lý do.")
    @PostMapping("/{id}/cancel")
    LegalAdminViews.Detail cancel(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody(required = false) LegalAdminRequests.Cancel request) {
        return legal.cancel(principal, id, request);
    }
}
