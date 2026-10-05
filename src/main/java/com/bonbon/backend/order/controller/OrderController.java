package com.bonbon.backend.order.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.dto.OrderRequests;
import com.bonbon.backend.order.dto.OrderViews;
import com.bonbon.backend.order.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;

/** A customer's own orders; another customer's order id is simply not found. */
@Tag(name = ApiTags.CUSTOMER_ORDERS, description = "Đơn hàng của chính khách: đặt, xem, huỷ, xác nhận đã nhận. Đơn của người khác luôn là không tìm thấy.")
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasAuthority('order:create')")
@Validated
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Places a cash-on-delivery order. The same {@code Idempotency-Key} always returns the same order (200 on a
     * repeat, 201 the first time), so a retry after a timeout or a double tap never creates a second one.
     */
    @Operation(operationId = "placeOrder", summary = "Đặt đơn", description = "Hiện chỉ có thanh toán khi nhận hàng (`COD`). Không gửi giá: máy chủ tính lại toàn bộ (giá món, lựa chọn, phí giao, ngưỡng miễn phí) và chụp lại vào đơn. Header `Idempotency-Key` (8–100 ký tự) bắt buộc: cùng khoá luôn trả về cùng một đơn (201 lần đầu, 200 khi lặp lại), nên thử lại hay bấm hai lần không tạo đơn thứ hai. Lỗi theo từng món có thêm `menuItemId`.")
    @ApiError(status = 404, code = "VENDOR_NOT_FOUND", when = "Quán không tồn tại hoặc chưa được duyệt.")
    @ApiError(status = 404, code = "ADDRESS_NOT_FOUND", when = "Địa chỉ không phải của khách.")
    @ApiError(status = 409, code = "SHOP_CLOSED", when = "Quán đang đóng cửa hoặc tạm ngưng nhận đơn.")
    @ApiError(status = 409, code = "CANNOT_ORDER_OWN_SHOP", when = "Không đặt được từ quán của chính mình.")
    @ApiError(status = 409, code = "TOO_MANY_OPEN_ORDERS", when = "Đang có quá nhiều đơn chưa xong (mặc định 3).")
    @ApiError(status = 409, code = "OUT_OF_DELIVERY_RADIUS", when = "Địa chỉ ngoài vùng giao của quán.")
    @ApiError(status = 409, code = "ITEM_UNAVAILABLE", when = "Một món đã hết hoặc không còn bán.")
    @ApiError(status = 409, code = "OPTION_UNAVAILABLE", when = "Một lựa chọn đã hết.")
    @ApiError(status = 409, code = "INSUFFICIENT_STOCK", when = "Món không còn đủ số phần.")
    @ApiError(status = 400, code = "OPTION_COUNT_INVALID", when = "Số lựa chọn trong một nhóm ngoài min/max.")
    @ApiError(status = 400, code = "OPTION_DUPLICATED", when = "Một lựa chọn bị chọn hai lần.")
    @ApiError(status = 400, code = "OPTION_NOT_OFFERED", when = "Lựa chọn không thuộc món.")
    @ApiError(status = 400, code = "BELOW_MIN_ORDER", when = "Chưa đạt đơn tối thiểu của quán; có `minOrderValue`.")
    @ApiResponse(responseCode = "201", description = "Đã tạo đơn mới.", content = @Content(schema = @Schema(implementation = OrderViews.Detail.class)))
    @ApiResponse(responseCode = "200", description = "Khoá `Idempotency-Key` đã dùng: trả về đúng đơn đã tạo trước đó.", content = @Content(schema = @Schema(implementation = OrderViews.Detail.class)))
    @PostMapping
    ResponseEntity<OrderViews.Detail> place(CurrentPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody OrderRequests.Place request, HttpServletRequest http) {
        OrderService.Placed placed = orders.place(principal, idempotencyKey, request, ClientContext.from(http),
                http.getHeader("User-Agent"));
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(placed.order());
    }

    /** Newest first. {@code status} may be repeated or comma separated; empty means every status. */
    @Operation(operationId = "listMyOrders", summary = "Danh sách đơn của tôi", description = "Mới nhất trước; `status` lặp lại được, bỏ trống là mọi trạng thái. Mỗi đơn có tóm tắt món.")
    @ApiError(status = 400, code = "INVALID_PAGE", when = "`page` âm hoặc `size` ngoài 1–50.")
    @GetMapping
    OrderViews.Page list(CurrentPrincipal principal, @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.list(principal.id(), status, page, size);
    }

    @Operation(operationId = "getMyOrder", summary = "Chi tiết đơn", description = "Bản chụp lúc đặt (tên, giá, lựa chọn, địa chỉ) và tiến trình; người thực hiện phía quán chỉ hiện là `SHOP`.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @GetMapping("/{id}")
    OrderViews.Detail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.get(principal.id(), id);
    }

    /** Free until the shop starts preparing; {@code reason} is optional. */
    @Operation(operationId = "cancelMyOrder", summary = "Huỷ đơn", description = "Miễn phí khi đơn còn `PLACED` hoặc `CONFIRMED`; quán đã bắt đầu chuẩn bị thì không huỷ được. Lý do không bắt buộc.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:cancel')")
    OrderViews.Detail cancel(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody(required = false) OrderRequests.Cancel request) {
        return orders.cancel(principal.id(), id, request == null ? null : request.reason());
    }

    /** The customer has it in hand; the shop can also mark it delivered and the first one wins. */
    @Operation(operationId = "confirmOrderReceived", summary = "Xác nhận đã nhận món", description = "Khi đơn đang giao. Quán cũng có thể đánh dấu đã giao; ai trước được tính.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Đơn không tồn tại hoặc không phải của người gọi (không tiết lộ đơn của người khác).")
    @ApiError(status = 409, code = "INVALID_TRANSITION", when = "Trạng thái hiện tại không cho phép bước này; `status` là trạng thái hiện tại.")
    @ApiError(status = 409, code = "ORDER_ALREADY_CHANGED", when = "Người khác vừa đổi trạng thái đơn trước; tải lại đơn để xem trạng thái mới.")
    @PostMapping("/{id}/received")
    OrderViews.Detail received(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.received(principal.id(), id);
    }
}
