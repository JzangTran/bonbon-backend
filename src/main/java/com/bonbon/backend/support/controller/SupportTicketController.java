package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.TicketRequests;
import com.bonbon.backend.support.dto.TicketViews;
import com.bonbon.backend.support.service.SupportTicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = ApiTags.SUPPORT_TICKETS, description = "Khách và người bán gửi phiếu cho đội hỗ trợ về việc chưa có hướng dẫn hoặc luồng báo cáo riêng (vấn đề về đơn đã giao hãy dùng báo cáo trên đơn). Phiếu `OPEN` đang chờ hỗ trợ; khi hỗ trợ trả lời thành `ANSWERED` và bạn được báo; bạn trả lời tiếp thì quay lại `OPEN`. Phiếu đã trả lời mà không ai nhắn thêm trong 7 ngày tự đóng. Mỗi người có tối đa 3 phiếu chưa xong. Bạn không bao giờ thấy tên quản trị viên đã trả lời.")
@RestController
@RequestMapping("/api/support")
@Validated
class SupportTicketController {

    private final SupportTicketService tickets;

    SupportTicketController(SupportTicketService tickets) {
        this.tickets = tickets;
    }

    @Operation(operationId = "openSupportTicket", summary = "Gửi phiếu hỗ trợ", description = "Có thể gắn một đơn của chính bạn (khách) hoặc của cửa hàng của bạn (người bán) và tối đa 3 ảnh đã tải lên qua `support/attachments`.")
    @ApiError(status = 409, code = "TOO_MANY_OPEN_TICKETS", when = "Đã có 3 phiếu chưa xong.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không thuộc tài khoản của người gọi.")
    @ApiError(status = 400, code = "ATTACHMENT_INVALID", when = "Khoá ảnh không phải ảnh người gọi đã tải lên hoặc bị lặp.")
    @ApiError(status = 403, code = "NOT_A_USER", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @ApiResponse(responseCode = "201", description = "Đã gửi.")
    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    TicketViews.Detail open(CurrentPrincipal principal, @Valid @RequestBody TicketRequests.Open request) {
        return tickets.open(principal, request);
    }

    @Operation(operationId = "listSupportTickets", summary = "Phiếu hỗ trợ của tôi", description = "Phiếu của người gọi ở vai trò đang dùng, hoạt động gần nhất trước.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @ApiError(status = 403, code = "NOT_A_USER", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @GetMapping("/tickets")
    TicketViews.Page list(CurrentPrincipal principal, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return tickets.list(principal, page, size);
    }

    @Operation(operationId = "getSupportTicket", summary = "Chi tiết phiếu", description = "Toàn bộ tin nhắn của phiếu theo thứ tự thời gian; `author` là `USER` hoặc `SUPPORT`. Ảnh là liên kết ngắn hạn.")
    @ApiError(status = 404, code = "TICKET_NOT_FOUND", when = "Không có phiếu này của người gọi.")
    @GetMapping("/tickets/{id}")
    TicketViews.Detail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return tickets.get(principal, id);
    }

    @Operation(operationId = "replySupportTicket", summary = "Nhắn thêm vào phiếu", description = "Phiếu đã trả lời quay lại chờ hỗ trợ (`OPEN`). Không nhắn thêm được vào phiếu đã đóng.")
    @ApiError(status = 404, code = "TICKET_NOT_FOUND", when = "Không có phiếu này của người gọi.")
    @ApiError(status = 409, code = "TICKET_CLOSED", when = "Phiếu đã đóng.")
    @ApiError(status = 400, code = "ATTACHMENT_INVALID", when = "Khoá ảnh không phải ảnh người gọi đã tải lên hoặc bị lặp.")
    @ApiError(status = 429, code = "TOO_MANY_MESSAGES", when = "Quá 30 tin trong một giờ.")
    @PostMapping("/tickets/{id}/messages")
    TicketViews.Detail reply(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TicketRequests.Message request) {
        return tickets.reply(principal, id, request);
    }

    @Operation(operationId = "closeSupportTicket", summary = "Đóng phiếu", description = "Người dùng tự đóng khi đã xong việc. Đóng rồi không mở lại được; cần hỗ trợ thì gửi phiếu mới.")
    @ApiError(status = 404, code = "TICKET_NOT_FOUND", when = "Không có phiếu này của người gọi.")
    @ApiError(status = 409, code = "TICKET_CLOSED", when = "Phiếu đã đóng.")
    @PostMapping("/tickets/{id}/close")
    TicketViews.Detail close(CurrentPrincipal principal, @PathVariable UUID id) {
        return tickets.close(principal, id);
    }

    @Operation(operationId = "uploadSupportAttachment", summary = "Tải ảnh đính kèm", description = "Tải một ảnh (JPEG, PNG hoặc WEBP, tối đa 5 MB, kiểm tra theo nội dung tệp) trước khi gửi phiếu hoặc tin nhắn; ảnh lưu riêng tư. Giới hạn 20 ảnh mỗi giờ.")
    @ApiError(status = 413, code = "FILE_TOO_LARGE", when = "Ảnh vượt 5 MB.")
    @ApiError(status = 415, code = "UNSUPPORTED_FILE_TYPE", when = "Định dạng không được nhận (kiểm tra theo nội dung tệp).")
    @ApiError(status = 429, code = "TOO_MANY_UPLOADS", when = "Tải quá 20 ảnh trong một giờ.")
    @ApiError(status = 403, code = "NOT_A_USER", when = "Vai trò đang dùng không phải khách hoặc người bán.")
    @PostMapping(path = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    TicketViews.Upload upload(CurrentPrincipal principal, @RequestPart("file") MultipartFile file) {
        return tickets.upload(principal, file);
    }
}
