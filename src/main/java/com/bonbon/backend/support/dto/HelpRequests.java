package com.bonbon.backend.support.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class HelpRequests {

    private HelpRequests() {
    }

    @Schema(name = "CreateHelpArticleRequest")
    public record Create(
            @Schema(description = "Tiêu đề hoặc câu hỏi, tối đa 200 ký tự.") @NotBlank @Size(max = 200) String title,
            @Schema(description = "Nội dung trả lời, tối đa 10.000 ký tự.") @NotBlank @Size(max = 10000) String body,
            @Schema(description = "ALL (mọi người), CUSTOMER (chỉ khách) hoặc SELLER (chỉ người bán).")
            @NotNull @Pattern(regexp = "ALL|CUSTOMER|SELLER", message = "Đối tượng phải là ALL, CUSTOMER hoặc SELLER.") String audience,
            @Schema(description = "Từ khoá tìm kiếm, tối đa 20, mỗi từ khoá tối đa 50 ký tự; chữ hoa và dấu không quan trọng.") @Size(max = 20) List<@NotBlank @Size(max = 50) String> keywords,
            @Schema(description = "DRAFT (chưa hiển thị) hoặc PUBLISHED. Mặc định DRAFT.")
            @Pattern(regexp = "DRAFT|PUBLISHED", message = "Trạng thái phải là DRAFT hoặc PUBLISHED.") String status) {
    }

    @Schema(name = "UpdateHelpArticleRequest", description = "Chỉ các trường được gửi mới đổi; `keywords` được gửi sẽ thay toàn bộ danh sách từ khoá.")
    public record Update(
            @Size(min = 1, max = 200) String title,
            @Size(min = 1, max = 10000) String body,
            @Pattern(regexp = "ALL|CUSTOMER|SELLER", message = "Đối tượng phải là ALL, CUSTOMER hoặc SELLER.") String audience,
            @Size(max = 20) List<@NotBlank @Size(max = 50) String> keywords,
            @Pattern(regexp = "DRAFT|PUBLISHED", message = "Trạng thái phải là DRAFT hoặc PUBLISHED.") String status) {
    }
}
