package com.bonbon.backend.legal.controller;

import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.LegalConsentService;
import com.bonbon.backend.legal.LegalDocumentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.LEGAL, description = "Điều khoản sử dụng và chính sách bảo mật đang có hiệu lực.")
@RestController
@RequestMapping("/api/legal/documents")
class LegalDocumentController {

    private final LegalConsentService legal;

    LegalDocumentController(LegalConsentService legal) {
        this.legal = legal;
    }

    /** Public: the version in force, shown and linked from registration forms. */
    @Operation(operationId = "getLegalDocument", summary = "Văn bản đang hiệu lực", description = "Nội dung và id phiên bản; biểu mẫu đăng ký gửi các id này trong danh sách đã đồng ý.")
    @GetMapping("/{type}")
    LegalDocumentView current(@PathVariable DocumentType type) {
        return legal.current(type);
    }
}
