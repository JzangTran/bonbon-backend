package com.bonbon.backend.orderfulfillment.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.orderfulfillment.dto.MerchantReviewRequests;
import com.bonbon.backend.orderfulfillment.service.MerchantReviewService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The reviews of the caller's own shop; a review of another shop is simply not found. */
@Tag(name = ApiTags.SELLER_REVIEWS, description = "Đánh giá của khách về quán của người gọi và phản hồi của quán: mỗi đánh giá một phản hồi, sửa hoặc xoá được trong 24 giờ.")
@RestController
@RequestMapping("/api/merchant/reviews")
@PreAuthorize("hasAuthority('review:respond')")
class MerchantReviewController {

    private final MerchantReviewService reviews;

    MerchantReviewController(MerchantReviewService reviews) {
        this.reviews = reviews;
    }

    @Operation(operationId = "listReviewsOfMyShop", summary = "Đánh giá của quán", description = "Mới nhất trước. `unreplied=true` chỉ lấy đánh giá chưa phản hồi. Đánh giá bị quản trị ẩn không hiện. Phản hồi của quán có `editableUntil` và nhãn `hidden` nếu bị ẩn.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    ReviewViews.Page list(CurrentPrincipal principal, @RequestParam(defaultValue = "false") boolean unreplied,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviews.list(principal, unreplied, page, size);
    }

    @Operation(operationId = "replyToReview", summary = "Phản hồi đánh giá", description = "Mỗi đánh giá một phản hồi, công khai dưới đánh giá và khách cũng thấy.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đánh giá không tồn tại, không phải của quán này hoặc đang bị ẩn.")
    @ApiError(status = 409, code = "ALREADY_RESPONDED", when = "Đánh giá đã có phản hồi; dùng sửa phản hồi.")
    @ApiResponse(responseCode = "201", description = "Đã đăng phản hồi.")
    @PostMapping("/{id}/response")
    @ResponseStatus(HttpStatus.CREATED)
    ReviewViews.Review respond(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantReviewRequests.Reply request) {
        return reviews.respond(principal, id, request.text());
    }

    @Operation(operationId = "editReviewReply", summary = "Sửa phản hồi", description = "Trong 24 giờ kể từ lúc đăng phản hồi. Phản hồi đã bị ẩn thì không sửa được.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đánh giá không tồn tại, không phải của quán này hoặc đang bị ẩn.")
    @ApiError(status = 404, code = "RESPONSE_NOT_FOUND", when = "Đánh giá chưa có phản hồi.")
    @ApiError(status = 409, code = "RESPONSE_LOCKED", when = "Đã quá 24 giờ kể từ lúc đăng phản hồi.")
    @ApiError(status = 409, code = "RESPONSE_HIDDEN", when = "Phản hồi đã bị quản trị ẩn.")
    @PutMapping("/{id}/response")
    ReviewViews.Review edit(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantReviewRequests.Reply request) {
        return reviews.editResponse(principal, id, request.text());
    }

    @Operation(operationId = "deleteReviewReply", summary = "Xoá phản hồi", description = "Trong 24 giờ kể từ lúc đăng phản hồi; sau đó có thể phản hồi lại.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "REVIEW_NOT_FOUND", when = "Đánh giá không tồn tại, không phải của quán này hoặc đang bị ẩn.")
    @ApiError(status = 404, code = "RESPONSE_NOT_FOUND", when = "Đánh giá chưa có phản hồi.")
    @ApiError(status = 409, code = "RESPONSE_LOCKED", when = "Đã quá 24 giờ kể từ lúc đăng phản hồi.")
    @ApiError(status = 409, code = "RESPONSE_HIDDEN", when = "Phản hồi đã bị quản trị ẩn.")
    @ApiResponse(responseCode = "204", description = "Đã xoá.")
    @DeleteMapping("/{id}/response")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(CurrentPrincipal principal, @PathVariable UUID id) {
        reviews.deleteResponse(principal, id);
    }
}
