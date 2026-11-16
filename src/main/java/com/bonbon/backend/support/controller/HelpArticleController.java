package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.support.dto.HelpViews;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.support.dto.HelpChatDtos;
import com.bonbon.backend.support.service.HelpArticleService;
import com.bonbon.backend.support.service.HelpChatService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final HelpChatService chat;

    HelpArticleController(HelpArticleService help, HelpChatService chat) {
        this.help = help;
        this.chat = chat;
    }

    @Operation(operationId = "askHelpChat", summary = "Hỏi trợ lý trả lời tự động",
            description = "Không cần đăng nhập. So khớp câu hỏi với từ khoá của các bài trợ giúp đã đăng dành cho người hỏi (không phân biệt dấu), trả nguyên văn bài phù hợp nhất; "
                    + "không có mô hình sinh văn bản nên không bịa câu trả lời. Không nhớ câu trước: mỗi câu hỏi được trả lời riêng. Tối đa 20 câu một phút.")
    @ApiError(status = 429, code = "TOO_MANY_QUESTIONS", when = "Quá 20 câu hỏi trong một phút.")
    @PostMapping("/ask")
    HelpChatDtos.Answer ask(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HelpChatDtos.Ask request, HttpServletRequest http) {
        String role = role(jwt);
        String audience = role != null ? ("SELLER".equals(role) ? "SELLER" : "CUSTOMER") : (request.audience() == null ? "CUSTOMER" : request.audience());
        java.util.UUID asker = jwt == null || jwt.getSubject() == null ? null : java.util.UUID.fromString(jwt.getSubject());
        return chat.ask(request.question(), audience, asker, ClientContext.from(http).ip());
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
