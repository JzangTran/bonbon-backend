package com.bonbon.backend.marketplace.merchant.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.dto.QuickReplyDtos;
import com.bonbon.backend.marketplace.merchant.service.QuickReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Quick replies for the shop's chat: "/" + shortcut in the composer inserts the text. */
@Tag(name = ApiTags.SELLER_SHOP)
@RestController
@RequestMapping("/api/merchant/quick-replies")
class QuickReplyController {

    private final QuickReplyService replies;

    QuickReplyController(QuickReplyService replies) {
        this.replies = replies;
    }

    @Operation(operationId = "listQuickReplies", summary = "Tin trả lời mẫu", description = "Theo thứ tự đã tạo. Lần đầu mở, quán được tạo sẵn ba tin gợi ý.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping
    @PreAuthorize("hasAuthority('conversation:read')")
    List<QuickReplyDtos.View> list(CurrentPrincipal principal) {
        return replies.list(principal);
    }

    @Operation(operationId = "createQuickReply", summary = "Thêm tin trả lời mẫu", description = "Tối đa 30 tin; mã gõ tắt không trùng trong quán.")
    @ApiError(status = 409, code = "QUICK_REPLY_SHORTCUT_TAKEN", when = "Mã gõ tắt đã có.")
    @ApiError(status = 409, code = "TOO_MANY_QUICK_REPLIES", when = "Đã đủ 30 tin.")
    @PostMapping
    @PreAuthorize("hasAuthority('conversation:write')")
    @ResponseStatus(HttpStatus.CREATED)
    QuickReplyDtos.View create(CurrentPrincipal principal, @Valid @RequestBody QuickReplyDtos.Request request) {
        return replies.create(principal, request);
    }

    @Operation(operationId = "updateQuickReply", summary = "Sửa tin trả lời mẫu", description = "Đổi mã gõ tắt hoặc nội dung.")
    @ApiError(status = 404, code = "QUICK_REPLY_NOT_FOUND", when = "Không có tin này trong quán.")
    @ApiError(status = 409, code = "QUICK_REPLY_SHORTCUT_TAKEN", when = "Mã gõ tắt đã có.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('conversation:write')")
    QuickReplyDtos.View update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody QuickReplyDtos.Request request) {
        return replies.update(principal, id, request);
    }

    @Operation(operationId = "deleteQuickReply", summary = "Xoá tin trả lời mẫu", description = "Xoá hẳn; ba tin gợi ý không được tạo lại.")
    @ApiError(status = 404, code = "QUICK_REPLY_NOT_FOUND", when = "Không có tin này trong quán.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('conversation:write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentPrincipal principal, @PathVariable UUID id) {
        replies.delete(principal, id);
    }
}
