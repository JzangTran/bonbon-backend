package com.bonbon.backend.order.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.dto.ReviewRequests;
import com.bonbon.backend.order.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

/** A customer's review of one of their own delivered orders; another customer's order is simply not found. */
@Tag(name = ApiTags.CUSTOMER_REVIEWS, description = "Đánh giá quán sau khi đơn đã giao: mỗi đơn một đánh giá, sửa hoặc xoá được trong 24 giờ.")
@RestController
@RequestMapping("/api/orders/{orderId}/review")
@PreAuthorize("hasAuthority('review:create')")
class ReviewController {

    private final ReviewService reviews;

    ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(operationId = "reviewOrder", summary = "Đánh giá đơn", description = "Chỉ đơn của chính khách và đã giao (`DELIVERED`), mỗi đơn một đánh giá. Số sao 1–5, nhận xét tối đa 1000 ký tự (có thể bỏ trống). Điểm trung bình của quán được cập nhật ngay.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 409, code = "REVIEW_NOT_ALLOWED", when = "Đơn chưa giao xong nên chưa đánh giá được.")
    @ApiError(status = 409, code = "ALREADY_REVIEWED", when = "Đơn này đã có đánh giá.")
    @ApiResponse(responseCode = "201", description = "Đã tạo đánh giá.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReviewViews.Review create(CurrentPrincipal principal, @PathVariable UUID orderId, @Valid @RequestBody ReviewRequests.Write request) {
        return reviews.post(principal.id(), orderId, request.rating(), request.comment());
    }

    @Operation(operationId = "getMyOrderReview", summary = "Xem đánh giá của tôi", description = "Gồm hạn sửa (`editableUntil`), phản hồi của quán nếu có, và nhãn `hidden` kèm `hiddenReason` nếu quản trị đã ẩn đánh giá (khách vẫn thấy đánh giá của mình).")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đơn chưa có đánh giá.")
    @GetMapping
    ReviewViews.Review get(CurrentPrincipal principal, @PathVariable UUID orderId) {
        return reviews.mine(principal.id(), orderId);
    }

    @Operation(operationId = "editMyOrderReview", summary = "Sửa đánh giá", description = "Trong 24 giờ kể từ lúc đăng. Đánh giá đã bị ẩn thì không sửa được.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đơn chưa có đánh giá.")
    @ApiError(status = 409, code = "REVIEW_LOCKED", when = "Đã quá 24 giờ kể từ lúc đăng.")
    @ApiError(status = 409, code = "REVIEW_HIDDEN", when = "Đánh giá đã bị quản trị ẩn.")
    @PutMapping
    ReviewViews.Review edit(CurrentPrincipal principal, @PathVariable UUID orderId, @Valid @RequestBody ReviewRequests.Write request) {
        return reviews.edit(principal.id(), orderId, request.rating(), request.comment());
    }

    @Operation(operationId = "deleteMyOrderReview", summary = "Xoá đánh giá", description = "Trong 24 giờ kể từ lúc đăng; phản hồi của quán cũng mất theo. Đánh giá đã bị ẩn thì không xoá được. Sau khi xoá có thể đánh giá lại đơn.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đơn chưa có đánh giá.")
    @ApiError(status = 409, code = "REVIEW_LOCKED", when = "Đã quá 24 giờ kể từ lúc đăng.")
    @ApiError(status = 409, code = "REVIEW_HIDDEN", when = "Đánh giá đã bị quản trị ẩn.")
    @ApiResponse(responseCode = "204", description = "Đã xoá.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentPrincipal principal, @PathVariable UUID orderId) {
        reviews.delete(principal.id(), orderId);
    }
}
