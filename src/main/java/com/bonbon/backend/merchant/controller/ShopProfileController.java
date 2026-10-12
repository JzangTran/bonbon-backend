package com.bonbon.backend.merchant.controller;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.merchant.service.ShopProfileService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** An approved shop editing itself; reading stays on GET /api/merchant/shop. */
@Tag(name = ApiTags.SELLER_SHOP, description = "Quán đã được duyệt tự sửa thông tin, giờ mở cửa và tạm ngưng nhận đơn. Xem bằng `GET /api/merchant/shop`.")
@RestController
@RequestMapping("/api/merchant/shop")
@PreAuthorize("hasAuthority('vendor:write')")
class ShopProfileController {

    private final ShopProfileService profiles;

    ShopProfileController(ShopProfileService profiles) {
        this.profiles = profiles;
    }

    /** Partial update; a changed address returns the shop to PENDING until approved again. */
    @Operation(operationId = "updateMyShop", summary = "Sửa thông tin quán", description = "Chỉ trường gửi lên mới đổi. Đổi địa chỉ đưa quán về chờ duyệt lại; các trường khác áp dụng ngay.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "DELIVERY_RADIUS_TOO_LARGE", when = "Vượt bán kính giao tối đa.")
    @ApiError(status = 400, code = "ADDRESS_NOT_FOUND", when = "`placeId` không tra được.")
    @ApiError(status = 503, code = "GEOCODING_UNAVAILABLE", when = "Dịch vụ tra địa chỉ (Goong) tạm thời không phản hồi; thử lại sau.")
    @PatchMapping
    ShopApplicationView update(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.Update request) {
        return profiles.update(principal, request);
    }

    /** Full replacement of the weekly schedule. */
    @Operation(operationId = "replaceOpeningHours", summary = "Đặt giờ mở cửa", description = "Thay toàn bộ lịch tuần; danh sách rỗng là đóng cả tuần. Khung qua nửa đêm thuộc ngày bắt đầu.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "OPENING_HOURS_INVALID", when = "Khung giờ không hợp lệ.")
    @ApiError(status = 400, code = "OPENING_HOURS_OVERLAP", when = "Các khung giờ chồng nhau.")
    @ApiError(status = 400, code = "OPENING_HOURS_TOO_MANY", when = "Quá nhiều khung giờ trong một ngày.")
    @PutMapping("/opening-hours")
    ShopApplicationView openingHours(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.OpeningHours request) {
        return profiles.replaceOpeningHours(principal, request);
    }

    @Operation(operationId = "closeMyShop", summary = "Đóng cửa hàng", description = "Đóng hẳn cửa hàng, không mở lại được. Cần đăng nhập trong 10 phút gần nhất. Quán chuyển sang `CLOSING`: biến mất khỏi danh sách, không nhận đơn mới, nhưng các đơn đang làm vẫn làm tiếp và xem được thu nhập. Quán thành `CLOSED` khi không còn đơn đang làm, khiếu nại hoặc kháng nghị chưa xong, không còn tiền bị giữ và sổ cái đã về 0 (số dương được chi trả, số âm được thu hoặc xoá bởi quản trị viên). Muốn bán lại phải mở cửa hàng mới.")
    @ApiError(status = 403, code = "REAUTH_REQUIRED", when = "Đăng nhập đã quá 10 phút.")
    @ApiError(status = 400, code = "REASON_TEXT_REQUIRED", when = "Lý do OTHER thiếu ghi chú.")
    @ApiError(status = 409, code = "SHOP_CANNOT_CLOSE", when = "Cửa hàng không ở trạng thái đang hoạt động hoặc bị đình chỉ; `status` cho biết trạng thái.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng.")
    @PostMapping("/close")
    ShopApplicationView close(CurrentPrincipal principal, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShopProfileRequests.Close request) {
        Object claim = jwt.getClaims().get(CurrentPrincipal.CLAIM_AUTH_TIME);
        return profiles.requestClose(principal, claim instanceof Number n ? java.time.Instant.ofEpochSecond(n.longValue()) : null, request, java.time.Instant.now());
    }

    @Operation(operationId = "setAcceptingOrders", summary = "Tạm ngưng / nhận đơn lại", description = "Ghi đè lịch ngay lập tức; đơn đã nhận không bị ảnh hưởng. Ai bấm và lúc nào được ghi lại.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @PutMapping("/accepting-orders")
    ShopApplicationView acceptingOrders(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.AcceptingOrders request) {
        return profiles.setAcceptingOrders(principal, request.accepting());
    }
}
