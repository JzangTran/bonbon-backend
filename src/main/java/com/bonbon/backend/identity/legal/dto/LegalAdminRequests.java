package com.bonbon.backend.identity.legal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.legal.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class LegalAdminRequests {

    private LegalAdminRequests() {
    }

    @Schema(name = "CreateLegalDraftRequest", description = "Tạo bản nháp phiên bản mới của một loại văn bản. Mỗi loại chỉ có một bản nháp cùng lúc.")
    public record CreateDraft(
            @NotNull DocumentType type,
            @NotBlank @Size(max = 200) String title,
            @Schema(description = "Tóm tắt thay đổi bằng lời dễ hiểu, hiện cho người dùng.") @Size(max = 2000) String summary,
            @NotBlank @Size(max = 200_000) String content,
            @Schema(description = "Thay đổi quan trọng: người dùng phải đồng ý lại. Mặc định true.") Boolean requiresReacceptance) {
    }

    @Schema(name = "UpdateLegalDraftRequest", description = "Chỉ các trường được gửi mới đổi; chỉ sửa được bản nháp.")
    public record UpdateDraft(
            @Size(min = 1, max = 200) String title,
            @Size(max = 2000) String summary,
            @Size(min = 1, max = 200_000) String content,
            Boolean requiresReacceptance) {
    }

    @Schema(name = "PublishLegalDocumentRequest")
    public record Publish(
            @Schema(description = "Ngày có hiệu lực. Thay đổi quan trọng phải cách bây giờ ít nhất `legal.min_notice_days` (15) ngày; bỏ trống là đúng số ngày đó. Thay đổi nhỏ mặc định có hiệu lực ngay.") Instant effectiveAt) {
    }

    @Schema(name = "CancelLegalDocumentRequest")
    public record Cancel(@Schema(description = "Bắt buộc khi huỷ bản đã đăng; người dùng được báo lý do.") @Size(max = 500) String reason) {
    }

    @Schema(name = "AcceptLegalDocumentsRequest")
    public record Accept(@Schema(description = "Id của các văn bản đang chờ đồng ý, đúng như `GET /api/legal/pending`.") @NotEmpty List<@NotNull UUID> documentIds) {
    }
}
