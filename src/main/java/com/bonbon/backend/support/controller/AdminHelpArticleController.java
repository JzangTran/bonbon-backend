package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.HelpRequests;
import com.bonbon.backend.support.dto.HelpViews;
import com.bonbon.backend.support.service.HelpArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_HELP, description = "Quản trị viên soạn bài cho trung tâm trợ giúp: tiêu đề, nội dung, đối tượng (mọi người, khách, người bán), từ khoá tìm kiếm và trạng thái nháp hoặc đã đăng. Bài nháp chỉ quản trị viên thấy. Xoá là xoá hẳn.")
@RestController
@RequestMapping("/api/admin/help-articles")
@PreAuthorize("hasAuthority('help-center:write')")
@Validated
class AdminHelpArticleController {

    private final HelpArticleService help;

    AdminHelpArticleController(HelpArticleService help) {
        this.help = help;
    }

    @Operation(operationId = "listAdminHelpArticles", summary = "Danh sách bài trợ giúp (kể cả nháp)", description = "Mới cập nhật trước. `status` lọc DRAFT hoặc PUBLISHED, `q` tìm như phía người dùng.")
    @ApiError(status = 400, code = "INVALID_STATUS", when = "`status` không phải DRAFT hoặc PUBLISHED.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    HelpViews.AdminArticles list(@RequestParam(required = false) String status, @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return help.list(status, q, page, size);
    }

    @Operation(operationId = "getAdminHelpArticle", summary = "Chi tiết một bài", description = "Bài ở bất kỳ trạng thái nào.")
    @ApiError(status = 404, code = "ARTICLE_NOT_FOUND", when = "Không có bài này.")
    @GetMapping("/{id}")
    HelpViews.AdminArticle get(@PathVariable UUID id) {
        return help.adminGet(id);
    }

    @Operation(operationId = "createHelpArticle", summary = "Tạo bài trợ giúp", description = "Mặc định là nháp; đặt `status` là PUBLISHED để đăng ngay. Từ khoá được chuẩn hoá về chữ thường và bỏ trùng.")
    @ApiResponse(responseCode = "201", description = "Đã tạo.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    HelpViews.AdminArticle create(CurrentPrincipal principal, @Valid @RequestBody HelpRequests.Create request) {
        return help.create(principal.id(), request);
    }

    @Operation(operationId = "updateHelpArticle", summary = "Sửa bài trợ giúp", description = "Chỉ các trường được gửi mới đổi. Đổi `status` về DRAFT để gỡ bài khỏi người dùng mà không xoá.")
    @ApiError(status = 404, code = "ARTICLE_NOT_FOUND", when = "Không có bài này.")
    @PatchMapping("/{id}")
    HelpViews.AdminArticle update(@PathVariable UUID id, @Valid @RequestBody HelpRequests.Update request) {
        return help.update(id, request);
    }

    @Operation(operationId = "deleteHelpArticle", summary = "Xoá bài trợ giúp", description = "Xoá hẳn, không hoàn tác được. Muốn tạm ẩn thì chuyển về nháp.")
    @ApiError(status = 404, code = "ARTICLE_NOT_FOUND", when = "Không có bài này.")
    @ApiResponse(responseCode = "204", description = "Đã xoá.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        help.delete(id);
    }
}
