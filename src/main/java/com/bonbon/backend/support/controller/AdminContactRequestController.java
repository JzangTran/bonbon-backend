package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.ContactRequests;
import com.bonbon.backend.support.dto.ContactViews;
import com.bonbon.backend.support.service.ContactRequestService;
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

@Tag(name = ApiTags.ADMIN_CONTACT, description = "Yêu cầu từ biểu mẫu liên hệ công khai. Người gửi có thể chưa có tài khoản; mọi câu trả lời đi qua email họ điền. Yêu cầu chưa trả lời xếp cũ nhất trước.")
@RestController
@RequestMapping("/api/admin/support/contact-requests")
@Validated
class AdminContactRequestController {

    private final ContactRequestService contacts;

    AdminContactRequestController(ContactRequestService contacts) {
        this.contacts = contacts;
    }

    @Operation(operationId = "listContactRequests", summary = "Hộp thư liên hệ", description = "Theo `status` (mặc định OPEN). OPEN xếp cũ nhất trước, các trạng thái khác mới nhất trước.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải OPEN, ANSWERED hoặc CLOSED.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    @PreAuthorize("hasAuthority('ticket:read')")
    ContactViews.Page inbox(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return contacts.inbox(status, page, size);
    }

    @Operation(operationId = "getContactRequest", summary = "Chi tiết yêu cầu liên hệ", description = "Nội dung người gửi viết và các câu trả lời đã gửi, kèm tên quản trị viên trả lời.")
    @ApiError(status = 404, code = "CONTACT_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ticket:read')")
    ContactViews.Detail get(@PathVariable UUID id) {
        return contacts.get(id);
    }

    @Operation(operationId = "answerContactRequest", summary = "Trả lời qua email", description = "Gửi câu trả lời tới email người gửi; yêu cầu thành `ANSWERED`. Trả lời thêm được khi chưa đóng.")
    @ApiError(status = 404, code = "CONTACT_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "CONTACT_REQUEST_CLOSED", when = "Yêu cầu đã đóng.")
    @PostMapping("/{id}/answer")
    @PreAuthorize("hasAuthority('ticket:reply')")
    ContactViews.Detail answer(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ContactRequests.Answer request) {
        return contacts.answer(principal, id, request);
    }

    @Operation(operationId = "closeContactRequest", summary = "Đóng yêu cầu", description = "Khi không cần trả lời thêm (đã xong, thư rác, trùng lặp). Không gửi email.")
    @ApiError(status = 404, code = "CONTACT_REQUEST_NOT_FOUND", when = "Không có yêu cầu này.")
    @ApiError(status = 409, code = "CONTACT_REQUEST_CLOSED", when = "Yêu cầu đã đóng.")
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('ticket:reply')")
    ContactViews.Detail close(@PathVariable UUID id) {
        return contacts.close(id);
    }
}
