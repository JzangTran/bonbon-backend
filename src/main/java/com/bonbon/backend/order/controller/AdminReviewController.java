package com.bonbon.backend.order.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.dto.ReviewRequests;
import com.bonbon.backend.order.service.ReviewModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_REVIEWS, description = "Ẩn hoặc khôi phục đánh giá và phản hồi vi phạm. Không xoá hẳn: mỗi lần ẩn hoặc khôi phục đều ghi nhật ký kèm người làm và lý do.")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasAuthority('review:moderate')")
class AdminReviewController {

    private final ReviewModerationService moderation;

    AdminReviewController(ReviewModerationService moderation) {
        this.moderation = moderation;
    }

    @Operation(operationId = "listReviewsForModeration", summary = "Danh sách đánh giá", description = "Mới nhất trước, gồm cả đánh giá đã ẩn (`hidden`). Lọc theo quán (`vendorId`), trạng thái ẩn (`hidden`) và số sao tối đa (`maxRating`, để tìm đánh giá thấp). Mỗi đánh giá kèm phản hồi của quán, kể cả khi phản hồi đang bị ẩn.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping("/reviews")
    ReviewViews.Page list(@RequestParam(required = false) UUID vendorId, @RequestParam(required = false) Boolean hidden,
            @RequestParam(required = false) Integer maxRating, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return moderation.list(vendorId, hidden, maxRating, page, size);
    }

    @Operation(operationId = "hideReview", summary = "Ẩn đánh giá", description = "Cần lý do. Đánh giá bị ẩn không còn tính vào điểm trung bình và số đánh giá của quán, và không hiện công khai; khách vẫn thấy đánh giá của mình kèm nhãn đã bị ẩn.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Không có đánh giá với id này.")
    @ApiError(status = 409, code = "ALREADY_HIDDEN", when = "Đánh giá đã bị ẩn.")
    @PostMapping("/reviews/{id}/hide")
    ReviewViews.Review hide(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ReviewRequests.Hide request) {
        return moderation.hideReview(principal.id(), id, request.reason());
    }

    @Operation(operationId = "unhideReview", summary = "Khôi phục đánh giá", description = "Đánh giá tính lại vào điểm trung bình của quán.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Không có đánh giá với id này.")
    @ApiError(status = 409, code = "NOT_HIDDEN", when = "Đánh giá không bị ẩn.")
    @PostMapping("/reviews/{id}/unhide")
    ReviewViews.Review unhide(CurrentPrincipal principal, @PathVariable UUID id) {
        return moderation.unhideReview(principal.id(), id);
    }

    @Operation(operationId = "hideReviewReply", summary = "Ẩn phản hồi của quán", description = "Cần lý do. Phản hồi bị ẩn không hiện công khai và khách không thấy; quán vẫn thấy phản hồi của mình kèm nhãn đã bị ẩn. `id` là id của phản hồi (`reply.id`), không phải của đánh giá.")
    @ApiError(status = 404, code = "RESPONSE_NOT_FOUND", when = "Không có phản hồi với id này.")
    @ApiError(status = 409, code = "ALREADY_HIDDEN", when = "Phản hồi đã bị ẩn.")
    @PostMapping("/review-responses/{id}/hide")
    ReviewViews.Review hideReply(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody ReviewRequests.Hide request) {
        return moderation.hideResponse(principal.id(), id, request.reason());
    }

    @Operation(operationId = "unhideReviewReply", summary = "Khôi phục phản hồi của quán", description = "`id` là id của phản hồi (`reply.id`).")
    @ApiError(status = 404, code = "RESPONSE_NOT_FOUND", when = "Không có phản hồi với id này.")
    @ApiError(status = 409, code = "NOT_HIDDEN", when = "Phản hồi không bị ẩn.")
    @PostMapping("/review-responses/{id}/unhide")
    ReviewViews.Review unhideReply(CurrentPrincipal principal, @PathVariable UUID id) {
        return moderation.unhideResponse(principal.id(), id);
    }
}
