package com.bonbon.backend.orderfulfillment.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import com.bonbon.backend.orderfulfillment.dto.MerchantOrderRequests;
import com.bonbon.backend.orderfulfillment.service.MerchantOrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The caller's own shop's orders; an order of another shop is simply not found. */
@Tag(name = ApiTags.SELLER_ORDERS, description = "Đơn hàng của quán người gọi. Đơn của quán khác là không tìm thấy; đơn chưa thanh toán không bao giờ hiện.")
@RestController
@RequestMapping("/api/merchant/orders")
class MerchantOrderController {

    private final MerchantOrderService orders;

    MerchantOrderController(MerchantOrderService orders) {
        this.orders = orders;
    }

    /**
     * {@code status} may be repeated (empty means all); {@code from} and {@code to} are Vietnam-time dates, both
     * inclusive; {@code sort} is {@code newest} (default) or {@code oldest} (the new-orders queue).
     */
    @Operation(operationId = "listShopOrders", summary = "Danh sách đơn của quán", description = "`status` lặp lại được, bỏ trống là mọi trạng thái. `from`/`to` là ngày theo giờ Việt Nam (gồm cả hai đầu). `sort=oldest` cho hàng chờ đơn mới. Đơn mới có `responseDeadline`, đơn đã nhận có `handoverDeadline`.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    ShopOrders.ShopOrderPage list(CurrentPrincipal principal, @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return orders.list(principal, status, from, to, "oldest".equals(sort), page, size);
    }

    @Operation(operationId = "getShopOrder", summary = "Chi tiết đơn", description = "Món, lựa chọn, ghi chú, liên hệ của khách (điện thoại và địa chỉ bị che sau khi hết thời hạn khiếu nại, trừ khi còn khiếu nại mở), tiến trình.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:read')")
    ShopOrders.ShopOrderDetail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.get(principal, id);
    }

    @Operation(operationId = "confirmShopOrder", summary = "Nhận đơn", description = "Đơn mới phải được trả lời trong 10 phút, nếu không hệ thống tự từ chối.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail confirm(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.move(principal, id, OrderStatus.CONFIRMED, null);
    }

    @Operation(operationId = "rejectShopOrder", summary = "Từ chối đơn", description = "Cần lý do (khách thấy). Tồn kho được trả lại; tính là lỗi của quán.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 400, code = "REASON_REQUIRED", when = "Thiếu lý do.")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail reject(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Reason request) {
        return orders.move(principal, id, OrderStatus.REJECTED, request.reason());
    }

    /** One step at a time: CONFIRMED to PREPARING, PREPARING to OUT_FOR_DELIVERY, then DELIVERED. */
    @Operation(operationId = "advanceShopOrder", summary = "Chuyển bước tiếp theo", description = "Từng bước một: `PREPARING` → `OUT_FOR_DELIVERY` → `DELIVERED`. Phải giao đi trong 90 phút kể từ lúc nhận đơn. Đơn COD thành đã thu tiền khi đã giao.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail status(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Step request) {
        return orders.move(principal, id, request.to(), null);
    }

    /** After confirming, the shop may still cancel (out of an ingredient); a reason is required and it counts against the shop. */
    @Operation(operationId = "cancelShopOrder", summary = "Huỷ đơn đã nhận", description = "Khi đơn đã nhận hoặc đang chuẩn bị, cần lý do; tồn kho được trả lại và tính là lỗi của quán.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt; `status` cho biết trạng thái hồ sơ.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 400, code = "REASON_REQUIRED", when = "Thiếu lý do.")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail cancel(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Reason request) {
        return orders.move(principal, id, OrderStatus.CANCELLED, request.reason());
    }
}
