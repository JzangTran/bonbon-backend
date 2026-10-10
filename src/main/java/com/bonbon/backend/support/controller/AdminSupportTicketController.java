package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.TicketRequests;
import com.bonbon.backend.support.dto.TicketViews;
import com.bonbon.backend.support.service.SupportTicketService;
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

@Tag(name = ApiTags.ADMIN_TICKETS, description = "Hộp thư phiếu hỗ trợ. Phiếu đang chờ xếp cũ nhất trước để phiếu chờ lâu nhất ở trên cùng. Phiếu không cho phép xem rộng hơn: muốn xem liên hệ của người dùng hay chi tiết đơn, dùng tra cứu (quyền riêng, có ghi nhật ký). Trả lời làm phiếu thành `ANSWERED`, gửi thông báo và email cho người dùng.")
@RestController
@RequestMapping("/api/admin/support/tickets")
@Validated
class AdminSupportTicketController {

    private final SupportTicketService tickets;

    AdminSupportTicketController(SupportTicketService tickets) {
        this.tickets = tickets;
    }

    @Operation(operationId = "listAdminSupportTickets", summary = "Hộp thư phiếu hỗ trợ", description = "Theo `status` (mặc định OPEN). OPEN và ANSWERED xếp cũ nhất trước, CLOSED mới nhất trước.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải OPEN, ANSWERED hoặc CLOSED.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    @PreAuthorize("hasAuthority('ticket:read')")
    TicketViews.AdminPage inbox(@RequestParam(defaultValue = "OPEN") String status, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return tickets.inbox(status, page, size);
    }

    @Operation(operationId = "getAdminSupportTicket", summary = "Chi tiết phiếu", description = "Toàn bộ tin nhắn kèm ảnh (liên kết ngắn hạn), người gửi (tên, phía khách hay cửa hàng) và đơn liên quan nếu có.")
    @ApiError(status = 404, code = "TICKET_NOT_FOUND", when = "Không có phiếu này.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ticket:read')")
    TicketViews.AdminDetail get(@PathVariable UUID id) {
        return tickets.adminGet(id);
    }

    @Operation(operationId = "answerSupportTicket", summary = "Trả lời phiếu", description = "Phiếu thành `ANSWERED`; người dùng nhận thông báo trong ứng dụng (và đẩy/email nếu họ để bật). Có thể trả lời thêm khi phiếu còn `ANSWERED`.")
    @ApiError(status = 404, code = "TICKET_NOT_FOUND", when = "Không có phiếu này.")
    @ApiError(status = 409, code = "TICKET_CLOSED", when = "Phiếu đã đóng.")
    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAuthority('ticket:reply')")
    TicketViews.AdminDetail reply(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody TicketRequests.Reply request) {
        return tickets.answer(principal, id, request);
    }
}
