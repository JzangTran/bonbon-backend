package com.bonbon.backend.ordering.order.controller;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.ordering.order.dto.FeedPage;
import com.bonbon.backend.ordering.order.service.HomeFeedService;
import com.bonbon.backend.ordering.order.service.ShopPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public: the customer home page, for guests and signed-in customers alike. */
@Tag(name = ApiTags.CUSTOMER_BROWSE)
@RestController
@RequestMapping("/api/feed")
class HomeFeedController {

    private final HomeFeedService feed;
    private final ShopPreferenceService preferences;

    HomeFeedController(HomeFeedService feed, ShopPreferenceService preferences) {
        this.feed = feed;
        this.preferences = preferences;
    }

    @Operation(operationId = "getHomeFeed", summary = "Danh sách quán ở trang chủ", description = """
            Mỗi trang 10 quán, mỗi quán tối đa 10 món đang bán (món giảm giá trước). Có toạ độ giao hàng: quán có bán kính giao \
            phủ tới điểm đó, gần trước. Không có toạ độ (khách chưa nhập địa chỉ): mọi quán, đánh giá cao trước. \
            `displayCategoryId` lọc theo danh mục ở trang chủ, gửi nhiều lần để lấy quán thuộc một trong các danh mục; \
            `deals=true` chỉ quán có món giảm giá, giảm nhiều trước. Bộ lọc: `sort=RATING` đánh giá cao trước (mặc định \
            `RECOMMENDED`); `highlyRated=true` đánh giá từ 4.4; `promotions=true` có món giảm giá hoặc voucher dùng được; \
            `bestSellers=true` từ 10 đơn đã giao trong 30 ngày; `nearby=true` trong 2 km (cần toạ độ). \
            Khách đã đăng nhập không thấy quán đã ẩn; khách chưa đăng nhập gửi quán đã ẩn trong `hide`. \
            `dealMaxPercent` là mức giảm cao nhất trong khu, cho dòng "Giảm đến X%". Không quán nào giao tới toạ độ: \
            `outOfArea=true` và danh sách là các quán gần nhất để xem, chưa đặt được.""")
    @ApiError(status = 400, code = "INVALID_POSITION", when = "Chỉ gửi một trong hai toạ độ, hoặc toạ độ ngoài phạm vi.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc quá lớn.")
    @ApiError(status = 400, code = "TOO_MANY_HIDDEN", when = "`hide` có hơn 100 quán.")
    @ApiError(status = 400, code = "INVALID_SORT", when = "`sort` không phải RECOMMENDED hay RATING.")
    @ApiError(status = 400, code = "TOO_MANY_CATEGORIES", when = "Gửi hơn 30 `displayCategoryId`.")
    @GetMapping
    FeedPage page(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
            @RequestParam(required = false) List<UUID> displayCategoryId, @RequestParam(defaultValue = "false") boolean deals,
            @RequestParam(required = false) List<UUID> hide, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "RECOMMENDED") String sort, @RequestParam(defaultValue = "false") boolean highlyRated,
            @RequestParam(defaultValue = "false") boolean promotions, @RequestParam(defaultValue = "false") boolean bestSellers,
            @RequestParam(defaultValue = "false") boolean nearby) {
        if (!sort.equals("RECOMMENDED") && !sort.equals("RATING")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Cách sắp xếp không hợp lệ.");
        }
        if (displayCategoryId != null && displayCategoryId.size() > 30) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_CATEGORIES", "Chọn quá nhiều loại ẩm thực.");
        }
        if (hide != null && hide.size() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_HIDDEN", "Danh sách quán ẩn quá dài.");
        }
        Instant now = Instant.now();
        Set<UUID> excluded = new HashSet<>(hide == null ? List.of() : hide);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            excluded.addAll(preferences.hiddenIds(CurrentPrincipal.from(jwt).id(), now));
        }
        Set<UUID> categories = displayCategoryId == null ? null : new HashSet<>(displayCategoryId);
        var filters = new HomeFeedService.Filters(sort.equals("RATING"), highlyRated, promotions, bestSellers, nearby);
        return feed.page(lat, lng, categories, deals, filters, excluded, page, now);
    }
}
