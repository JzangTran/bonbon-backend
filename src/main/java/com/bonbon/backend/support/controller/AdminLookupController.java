package com.bonbon.backend.support.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.support.dto.LookupRequests;
import com.bonbon.backend.support.dto.LookupViews;
import com.bonbon.backend.support.service.AdminLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.ADMIN_LOOKUP, description = "Quản trị viên tra cứu đơn và khách để xử lý tranh chấp, không để duyệt danh sách người dùng. Tìm kiếm cần một mã chính xác (mã đơn, email hoặc số điện thoại) và trả tối đa 20 kết quả. Số điện thoại, email và địa chỉ được che; muốn xem đầy đủ phải chọn lý do (`DISPUTE`, `DATA_REQUEST`, `SAFETY`, `OTHER`). Chỉ đọc, và mọi lần tìm, mở hoặc xem đầy đủ đều ghi nhật ký. Mọi thay đổi tiền hay trạng thái đi qua các luồng quyết định riêng.")
@RestController
@RequestMapping("/api/admin")
@Validated
class AdminLookupController {

    private final AdminLookupService lookup;

    AdminLookupController(AdminLookupService lookup) {
        this.lookup = lookup;
    }

    @Operation(operationId = "searchAdminOrders", summary = "Tìm đơn theo mã", description = "Đúng một trong `code` (mã đơn, có thể có dấu #), `customerEmail` hoặc `customerPhone` (tài khoản khách, khớp chính xác). Tối đa 20 đơn, mới nhất trước. Không có tham số nào hoặc nhiều hơn một thì bị từ chối.")
    @ApiError(status = 400, code = "IDENTIFIER_REQUIRED", when = "Không có hoặc có nhiều hơn một mã tìm kiếm.")
    @ApiError(status = 400, code = "CODE_INVALID", when = "`code` không phải một số.")
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('admin-order:read')")
    LookupViews.OrderResults searchOrders(CurrentPrincipal principal, @RequestParam(required = false) String code, @RequestParam(required = false) String customerEmail,
            @RequestParam(required = false) String customerPhone) {
        return lookup.searchOrders(principal, code, customerEmail, customerPhone);
    }

    @Operation(operationId = "getAdminOrder", summary = "Chi tiết đơn", description = "Món như lúc đặt, số tiền, thanh toán và hoàn tiền, lịch sử trạng thái đầy đủ (kèm ai thực hiện, để phân biệt hệ thống tự hết hạn với thao tác của quán), các khiếu nại của đơn và id cuộc trò chuyện giữa khách với quán. Liên hệ của khách đã che. Mở đơn được ghi nhật ký.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Không có đơn này.")
    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('admin-order:read')")
    LookupViews.OrderDetail order(CurrentPrincipal principal, @PathVariable UUID id) {
        return lookup.order(principal, id);
    }

    @Operation(operationId = "revealAdminOrderContact", summary = "Xem đầy đủ liên hệ của đơn", description = "Trả email và số điện thoại tài khoản, số điện thoại và địa chỉ giao hàng đầy đủ. Bắt buộc chọn lý do; `OTHER` cần ghi chú. Lý do được ghi nhật ký cùng người xem.")
    @ApiError(status = 404, code = "ORDER_NOT_FOUND", when = "Không có đơn này.")
    @ApiError(status = 400, code = "NOTE_REQUIRED", when = "Lý do OTHER thiếu ghi chú.")
    @PostMapping("/orders/{id}/reveal")
    @PreAuthorize("hasAuthority('admin-order:read')")
    LookupViews.RevealedOrder revealOrder(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody LookupRequests.Reveal request) {
        return lookup.revealOrder(principal, id, request);
    }

    @Operation(operationId = "searchAdminCustomers", summary = "Tìm tài khoản theo email hoặc số điện thoại", description = "Đúng một trong `email` hoặc `phone`, khớp chính xác. Tối đa 20 tài khoản (số điện thoại chưa được xác minh nên có thể trùng). Tài khoản quản trị không xuất hiện.")
    @ApiError(status = 400, code = "IDENTIFIER_REQUIRED", when = "Không có hoặc có cả hai mã tìm kiếm.")
    @GetMapping("/customers")
    @PreAuthorize("hasAuthority('admin-customer:read')")
    LookupViews.CustomerResults searchCustomers(CurrentPrincipal principal, @RequestParam(required = false) String email, @RequestParam(required = false) String phone) {
        return lookup.searchCustomers(principal, email, phone);
    }

    @Operation(operationId = "getAdminCustomer", summary = "Chi tiết tài khoản", description = "Vai trò, ngày tạo, đã xác minh email chưa và 10 đơn gần nhất. Không bao giờ có mật khẩu, token hay thiết bị nhận thông báo. Email và số điện thoại đã che. Mở tài khoản được ghi nhật ký.")
    @ApiError(status = 404, code = "CUSTOMER_NOT_FOUND", when = "Không có tài khoản này (hoặc là tài khoản quản trị).")
    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAuthority('admin-customer:read')")
    LookupViews.CustomerDetail customer(CurrentPrincipal principal, @PathVariable UUID id) {
        return lookup.customer(principal, id);
    }

    @Operation(operationId = "revealAdminCustomerContact", summary = "Xem đầy đủ email và số điện thoại", description = "Bắt buộc chọn lý do; `OTHER` cần ghi chú. Được ghi nhật ký cùng lý do.")
    @ApiError(status = 404, code = "CUSTOMER_NOT_FOUND", when = "Không có tài khoản này (hoặc là tài khoản quản trị).")
    @ApiError(status = 400, code = "NOTE_REQUIRED", when = "Lý do OTHER thiếu ghi chú.")
    @PostMapping("/customers/{id}/reveal")
    @PreAuthorize("hasAuthority('admin-customer:read')")
    LookupViews.RevealedCustomer revealCustomer(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody LookupRequests.Reveal request) {
        return lookup.revealCustomer(principal, id, request);
    }
}
