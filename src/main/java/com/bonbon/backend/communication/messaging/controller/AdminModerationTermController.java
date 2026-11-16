package com.bonbon.backend.communication.messaging.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.communication.messaging.dto.ChatViews;
import com.bonbon.backend.communication.messaging.service.MessageModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_REPORTS)
@RestController
@RequestMapping("/api/admin/moderation-terms")
@PreAuthorize("hasAuthority('moderation:write')")
class AdminModerationTermController {

    @Schema(name = "ModerationTermRequest")
    record Add(@NotBlank @Size(min = 2, max = 50) String term) {
    }

    @Schema(name = "ModerationTermActive")
    record Active(@NotNull Boolean active) {
    }

    private final MessageModerationService moderation;

    AdminModerationTermController(MessageModerationService moderation) {
        this.moderation = moderation;
    }

    @Operation(operationId = "listModerationTerms", summary = "Danh sách từ bị che trong tin nhắn",
            description = "Từ hoặc cụm từ bị thay bằng *** trong tin nhắn người nhận đọc, so khớp cả khi gõ không dấu. Nội dung gốc vẫn được lưu.")
    @GetMapping
    List<ChatViews.ModerationTerm> list() {
        return moderation.listTerms();
    }

    @Operation(operationId = "addModerationTerm", summary = "Thêm từ cần che", description = "Áp dụng cho tin nhắn đọc sau tối đa một phút.")
    @ApiError(status = 400, code = "TERM_INVALID", when = "Từ ngắn hơn 2 hoặc dài hơn 50 ký tự.")
    @ApiError(status = 409, code = "TERM_EXISTS", when = "Từ đã có (không phân biệt hoa thường).")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChatViews.ModerationTerm add(CurrentPrincipal principal, @Valid @RequestBody Add request) {
        return moderation.addTerm(principal.id(), request.term());
    }

    @Operation(operationId = "setModerationTermActive", summary = "Bật hoặc tắt một từ")
    @ApiError(status = 404, code = "TERM_NOT_FOUND", when = "Không có từ này.")
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setActive(@PathVariable UUID id, @Valid @RequestBody Active request) {
        moderation.setTermActive(id, request.active());
    }

    @Operation(operationId = "deleteModerationTerm", summary = "Xoá một từ")
    @ApiError(status = 404, code = "TERM_NOT_FOUND", when = "Không có từ này.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        moderation.deleteTerm(id);
    }
}
