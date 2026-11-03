package com.bonbon.backend.ordering.order.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import com.bonbon.backend.ordering.order.service.ShopPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The heart and "Ẩn quán này" of the home page: a customer's favourite and hidden shops. */
@Tag(name = ApiTags.CUSTOMER_FAVORITES, description = "Quán yêu thích và quán đã ẩn khỏi trang chủ (30 ngày).")
@RestController
@RequestMapping("/api/account")
@PreAuthorize("hasAuthority('order:create')")
class ShopPreferenceController {

    private final ShopPreferenceService preferences;
    private final ShopCatalog shops;

    ShopPreferenceController(ShopPreferenceService preferences, ShopCatalog shops) {
        this.preferences = preferences;
        this.shops = shops;
    }

    @Operation(operationId = "listFavoriteShops", summary = "Quán yêu thích", description = "Mới thêm trước, mỗi quán kèm tối đa 10 món đang bán như ở trang chủ. Gửi toạ độ để có khoảng cách. Quán đã đóng hoặc không còn được duyệt không có trong danh sách.")
    @GetMapping("/favorite-shops")
    List<ShopCatalog.FeedCard> favorites(CurrentPrincipal principal, @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return shops.feedCards(preferences.favoriteIds(principal.id()), lat, lng);
    }

    @Operation(operationId = "addFavoriteShop", summary = "Thêm vào mục yêu thích", description = "Thêm lại quán đã có không đổi gì.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Quán không tồn tại hoặc chưa được duyệt.")
    @ApiError(status = 409, code = "FAVORITES_FULL", when = "Đã có 200 quán yêu thích.")
    @PutMapping("/favorite-shops/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addFavorite(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        preferences.addFavorite(principal.id(), vendorId);
    }

    @Operation(operationId = "removeFavoriteShop", summary = "Bỏ khỏi mục yêu thích", description = "Bỏ quán không có trong danh sách không báo lỗi.")
    @DeleteMapping("/favorite-shops/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeFavorite(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        preferences.removeFavorite(principal.id(), vendorId);
    }

    @Operation(operationId = "hideShop", summary = "Ẩn quán này", description = "Quán không hiện ở trang chủ của bạn trong 30 ngày (ẩn lại thì tính lại 30 ngày). Trả về thời điểm quán hiện lại.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Quán không tồn tại hoặc chưa được duyệt.")
    @PutMapping("/hidden-shops/{vendorId}")
    HiddenShop hide(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        return new HiddenShop(vendorId, preferences.hide(principal.id(), vendorId, Instant.now()));
    }

    @Operation(operationId = "unhideShop", summary = "Hiện lại quán đã ẩn", description = "Quán hiện lại ở trang chủ ngay.")
    @DeleteMapping("/hidden-shops/{vendorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unhide(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        preferences.unhide(principal.id(), vendorId);
    }

    record HiddenShop(UUID vendorId, Instant hiddenUntil) {
    }
}
