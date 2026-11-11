package com.bonbon.backend.identity.legal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.legal.DocumentType;
import io.swagger.v3.oas.annotations.media.Schema;

public final class LegalAdminViews {

    private LegalAdminViews() {
    }

    @Schema(name = "LegalDocumentVersion")
    public record Version(
            UUID id,
            DocumentType type,
            int version,
            String title,
            String summary,
            boolean requiresReacceptance,
            @Schema(description = "DRAFT (chỉ quản trị viên thấy), SCHEDULED (đã đăng, chưa đến ngày hiệu lực), EFFECTIVE (đang hiệu lực), SUPERSEDED (đã có bản mới hơn hiệu lực) hoặc CANCELLED.") String status,
            Instant publishedAt,
            Instant effectiveAt,
            Instant cancelledAt,
            String cancelReason,
            @Schema(description = "SHA-256 của nội dung lúc đăng: bằng chứng văn bản không bị sửa.") String contentSha256,
            @Schema(description = "Số tài khoản đã đồng ý đúng phiên bản này; rỗng với bản nháp.") Long accepted,
            @Schema(description = "Số tài khoản thuộc đối tượng của văn bản này mà chưa đồng ý phiên bản này hoặc bản mới hơn; rỗng với bản nháp.") Long pending) {
    }

    @Schema(name = "LegalDocumentVersionList", description = "Mọi phiên bản, theo loại rồi phiên bản mới nhất trước.")
    public record Versions(List<Version> items) {
    }

    @Schema(name = "LegalDocumentDetail")
    public record Detail(Version version, String content) {
    }

    @Schema(name = "PendingLegalDocument", description = "Một văn bản người dùng phải đồng ý lại.")
    public record Pending(UUID id, DocumentType type, int version, String title, String summary, Instant effectiveAt) {
    }

    @Schema(name = "PendingLegalDocuments")
    public record PendingList(List<Pending> items) {
    }
}
