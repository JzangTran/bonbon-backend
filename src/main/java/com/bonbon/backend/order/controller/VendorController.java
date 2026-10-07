package com.bonbon.backend.order.controller;

import java.util.UUID;

import com.bonbon.backend.merchant.ShopCatalog;
import com.bonbon.backend.order.dto.VendorPage;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.service.ReviewService;
import com.bonbon.backend.order.service.VendorBrowseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Public: guests browse shops and menus before logging in. */
@Tag(name = ApiTags.CUSTOMER_BROWSE, description = "Tìm quán giao tới một điểm và xem thực đơn, không cần đăng nhập.")
@RestController
@RequestMapping("/api/vendors")
class VendorController {

    private final VendorBrowseService browse;
    private final ReviewService reviews;

    VendorController(VendorBrowseService browse, ReviewService reviews) {
        this.browse = browse;
        this.reviews = reviews;
    }

    /** {@code sort}: {@code distance} (default; open shops first) or {@code name}. */
    @Operation(operationId = "listShopsInArea", summary = "Quán giao tới điểm này", description = "Quán có bán kính giao riêng phủ tới toạ độ, quán đang mở trước rồi gần trước (`sort=name` để xếp theo tên). `q` tìm theo tên quán hoặc món, không phân biệt dấu. `categoryId` lọc theo ngành và mọi ngành con. Chỉ quán có ít nhất một món đặt được.")
    @ApiError(status = 400, code = "INVALID_POSITION", when = "Toạ độ ngoài phạm vi.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @ApiError(status = 400, code = "INVALID_QUERY", when = "Từ khoá quá dài (tối đa 100 ký tự).")
    @ApiError(status = 400, code = "INVALID_SORT", when = "`sort` chỉ nhận `distance` hoặc `name`.")
    @GetMapping
    VendorPage inArea(@RequestParam double lat, @RequestParam double lng, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return browse.inArea(lat, lng, q, categoryId, sort, page, size);
    }

    /** Sold-out dishes are returned with {@code soldOut: true}; the stock number is never exposed. */
    @Operation(operationId = "getShopMenu", summary = "Thực đơn của quán", description = "Các mục có món, món hết có `soldOut: true`, kèm nhóm lựa chọn. Không bao giờ lộ số tồn kho. Gửi toạ độ để có khoảng cách.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Quán không tồn tại hoặc chưa được duyệt.")
    @ApiError(status = 400, code = "INVALID_POSITION", when = "Chỉ gửi một trong hai toạ độ, hoặc toạ độ ngoài phạm vi.")
    @GetMapping("/{id}/menu")
    ShopCatalog.ShopMenu menu(@PathVariable UUID id, @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return browse.menu(id, lat, lng);
    }

    @Operation(operationId = "listShopReviews", summary = "Đánh giá của quán", description = "Công khai, mới nhất trước. Chỉ gồm đánh giá đang hiển thị (đánh giá bị quản trị ẩn không có ở đây) kèm phản hồi của quán nếu có. Kèm điểm trung bình (một chữ số thập phân) và số đánh giá. Tên người đánh giá được rút gọn, không có thông tin đơn.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Quán không tồn tại hoặc chưa được duyệt.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping("/{id}/reviews")
    ReviewViews.Page shopReviews(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return reviews.forShop(id, page, size);
    }
}
