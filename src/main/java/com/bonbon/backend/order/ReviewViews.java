package com.bonbon.backend.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a review looks like to each audience. One shape serves all of them and fields that do not apply to the
 * caller are left out (omitted from the JSON), so the public list never carries ids of orders or moderation notes.
 */
public final class ReviewViews {

    private ReviewViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "Review", description = "Một đánh giá. Các trường không thuộc người xem sẽ vắng mặt.")
    public record Review(
            UUID id,
            @Schema(description = "Đơn được đánh giá (khách, quán, quản trị; không có ở danh sách công khai).") UUID orderId,
            @Schema(description = "Mã đơn hiển thị (không có ở danh sách công khai).") Long orderNumber,
            @Schema(description = "Quán được đánh giá (khách và quản trị).") UUID vendorId,
            @Schema(description = "Số sao, 1–5.") int rating,
            String comment,
            @Schema(description = "Tên rút gọn của người đánh giá, ví dụ `An T.`.") String reviewerName,
            Instant createdAt,
            Instant updatedAt,
            @Schema(description = "Hạn cuối để khách sửa hoặc xoá (chỉ khách xem đánh giá của mình).") Instant editableUntil,
            @Schema(description = "Đánh giá đang bị quản trị ẩn (khách và quản trị).") Boolean hidden,
            @Schema(description = "Lý do ẩn (khách và quản trị).") String hiddenReason,
            @Schema(description = "Phản hồi của quán, nếu có và đang hiển thị với người xem.") Reply reply) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ReviewReply", description = "Phản hồi của quán cho một đánh giá.")
    public record Reply(
            @Schema(description = "Không có ở danh sách công khai.") UUID id,
            String text,
            Instant createdAt,
            Instant updatedAt,
            @Schema(description = "Hạn cuối để quán sửa hoặc xoá (chỉ quán xem phản hồi của mình).") Instant editableUntil,
            @Schema(description = "Phản hồi đang bị quản trị ẩn (quán và quản trị).") Boolean hidden,
            @Schema(description = "Lý do ẩn (quán và quản trị).") String hiddenReason) {
    }

    /** {@code ratingAverage} is null and {@code ratingCount} 0 while the shop has no visible review. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "ReviewPage")
    public record Page(List<Review> items, int page, int size, long total,
            @Schema(description = "Điểm trung bình một chữ số thập phân (chỉ ở danh sách công khai của quán).") Double ratingAverage,
            @Schema(description = "Số đánh giá đang hiển thị (chỉ ở danh sách công khai của quán).") Integer ratingCount) {
    }
}
