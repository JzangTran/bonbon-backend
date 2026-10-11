package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.support.dto.HelpViews;
import com.bonbon.backend.support.service.HelpArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.HELP_CENTER, description = "Trung tâm trợ giúp: các bài hướng dẫn và câu hỏi thường gặp. Không cần đăng nhập. Người bán thấy bài dành cho mọi người và bài dành cho người bán; khách và người chưa đăng nhập thấy bài dành cho mọi người và bài dành cho khách. Đối tượng được suy ra từ vai trò đang dùng, không phải tham số, nên khách không thể xem bài của người bán bằng cách đổi tham số.")
@RestController
@RequestMapping("/api/help-articles")
class HelpArticleController {

    private final HelpArticleService help;

    HelpArticleController(HelpArticleService help) {
        this.help = help;
    }

    @Operation(operationId = "searchHelpArticles", summary = "Tìm bài trợ giúp", description = "Chỉ các bài đã đăng, mới cập nhật trước, tối đa 50. `q` tìm trong tiêu đề, nội dung và từ khoá, không phân biệt hoa thường và dấu. Bỏ `q` để xem tất cả.")
    @GetMapping
    HelpViews.Articles search(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String q) {
        return help.search(role(jwt), q);
    }

    @Operation(operationId = "getHelpArticle", summary = "Xem một bài trợ giúp", description = "Bài đã đăng và dành cho người gọi.")
    @ApiError(status = 404, code = "ARTICLE_NOT_FOUND", when = "Bài không tồn tại, chưa đăng, hoặc không dành cho người gọi.")
    @GetMapping("/{id}")
    HelpViews.Article get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return help.get(role(jwt), id);
    }

    private static String role(Jwt jwt) {
        return jwt == null ? null : jwt.getClaimAsString("role");
    }
}
