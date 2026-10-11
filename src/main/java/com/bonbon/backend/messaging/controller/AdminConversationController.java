package com.bonbon.backend.messaging.controller;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.messaging.dto.ChatViews;
import com.bonbon.backend.messaging.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_CONVERSATIONS, description = "Quản trị viên đọc một cuộc trò chuyện khách – cửa hàng khi điều tra tranh chấp. Chỉ đọc, không có thao tác kiểm duyệt. Quyền riêng (`admin-conversation:read`), tách khỏi quyền xem đơn; mỗi lần mở cuộc trò chuyện được ghi vào nhật ký tra cứu. Id cuộc trò chuyện lấy từ chi tiết đơn.")
@RestController
@RequestMapping("/api/admin/conversations")
@PreAuthorize("hasAuthority('admin-conversation:read')")
@Validated
class AdminConversationController {

    private final ChatService chat;

    AdminConversationController(ChatService chat) {
        this.chat = chat;
    }

    @Operation(operationId = "getAdminConversation", summary = "Thông tin một cuộc trò chuyện", description = "Khách, cửa hàng và thời điểm của cuộc trò chuyện; không kèm tin nhắn và không ghi nhật ký.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Không có cuộc trò chuyện này.")
    @GetMapping("/{id}")
    ChatViews.AdminConversation get(@PathVariable UUID id) {
        return chat.adminConversation(id);
    }

    @Operation(operationId = "listAdminConversationMessages", summary = "Đọc tin nhắn của một cuộc trò chuyện", description = "Tin mới nhất trước, trang cũ hơn qua `before` như phía người dùng. Mở trang đầu (không có `before`) được ghi vào nhật ký tra cứu cùng người mở và thời điểm. `mine` luôn là `false`.")
    @ApiError(status = 404, code = "CONVERSATION_NOT_FOUND", when = "Không có cuộc trò chuyện này.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`size` ngoài 1–50.")
    @GetMapping("/{id}/messages")
    ChatViews.Messages messages(CurrentPrincipal principal, @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before, @RequestParam(defaultValue = "30") int size) {
        return chat.adminMessages(principal.id(), id, before, size);
    }
}
