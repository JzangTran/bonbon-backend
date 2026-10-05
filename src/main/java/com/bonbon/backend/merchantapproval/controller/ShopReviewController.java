package com.bonbon.backend.merchantapproval.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchantapproval.dto.ReviewDtos;
import com.bonbon.backend.merchantapproval.service.ShopReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = ApiTags.ADMIN_SHOP_REVIEW, description = "Duyệt hồ sơ mở cửa hàng và đặt bán kính giao tối đa của nền tảng.")
@RestController
@RequestMapping("/api/admin")
class ShopReviewController {

    private final ShopReviewService reviews;

    ShopReviewController(ShopReviewService reviews) {
        this.reviews = reviews;
    }

    /** Submitted applications, oldest first; drafts never appear. */
    @Operation(operationId = "listShopApplications", summary = "Hàng chờ duyệt", description = "Hồ sơ đã gửi, cũ nhất trước; bản nháp không bao giờ hiện. Lọc theo `status` (PENDING, APPROVED, REJECTED) và tìm theo tên, phường, tỉnh.")
    @ApiError(status = 400, code = "STATUS_FILTER_INVALID", when = "`status` không thuộc các giá trị cho phép.")
    @GetMapping("/merchant-approval/requests")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    List<ReviewDtos.QueueRow> queue(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return reviews.queue(status, q);
    }

    /** Shop, shipping, tax and the masked payout account, with earlier decisions; no identity documents. */
    @Operation(operationId = "getShopApplication", summary = "Xem hồ sơ", description = "Thông tin quán, giao hàng, thuế, tài khoản nhận tiền (dạng che) và các quyết định trước. Không gồm giấy tờ định danh.")
    @ApiError(status = 404, code = "SHOP_APPLICATION_NOT_FOUND", when = "Không có hồ sơ đã gửi với id này.")
    @GetMapping("/merchant-approval/requests/{vendorId}")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    ReviewDtos.Application application(@PathVariable UUID vendorId) {
        return reviews.application(vendorId);
    }

    /** Separate permission, and every call is audit-logged. Photo URLs expire after 5 minutes. */
    @Operation(operationId = "getShopIdentityDocuments", summary = "Xem giấy tờ định danh", description = "Quyền riêng, mỗi lần xem đều được ghi nhật ký. Liên kết ảnh hết hạn sau 5 phút.")
    @ApiError(status = 404, code = "SHOP_APPLICATION_NOT_FOUND", when = "Không có hồ sơ đã gửi với id này.")
    @GetMapping("/merchant-approval/requests/{vendorId}/identity")
    @PreAuthorize("hasAuthority('merchant-approval:read-identity')")
    ShopApplications.IdentityDocuments identity(CurrentPrincipal principal, @PathVariable UUID vendorId, HttpServletRequest http) {
        return reviews.identity(principal, vendorId, ClientContext.from(http).ip());
    }

    @Operation(operationId = "approveShop", summary = "Duyệt cửa hàng", description = "Quán được mở bán; chủ quán nhận email.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có quán với id này.")
    @ApiError(status = 409, code = "SHOP_NOT_PENDING", when = "Hồ sơ không ở trạng thái chờ duyệt.")
    @PostMapping("/merchant-approval/requests/{vendorId}/approve")
    @PreAuthorize("hasAuthority('merchant-approval:decide')")
    ShopApplications.Decided approve(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        return reviews.approve(principal, vendorId);
    }

    @Operation(operationId = "rejectShop", summary = "Từ chối hồ sơ", description = "Cần lý do; chủ quán nhận email, sửa và gửi lại được.")
    @ApiError(status = 404, code = "SHOP_NOT_FOUND", when = "Không có quán với id này.")
    @ApiError(status = 409, code = "SHOP_NOT_PENDING", when = "Hồ sơ không ở trạng thái chờ duyệt.")
    @PostMapping("/merchant-approval/requests/{vendorId}/reject")
    @PreAuthorize("hasAuthority('merchant-approval:decide')")
    ShopApplications.Decided reject(CurrentPrincipal principal, @PathVariable UUID vendorId,
            @Valid @RequestBody ReviewDtos.RejectRequest request) {
        return reviews.reject(principal, vendorId, request.reason());
    }

    @Operation(operationId = "getDeliveryRadiusCap", summary = "Xem bán kính giao tối đa", description = "Mức trần áp dụng cho bán kính giao riêng của mọi quán.")
    @GetMapping("/settings/delivery-radius-cap")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    ReviewDtos.RadiusCap radiusCap() {
        return reviews.radiusCap();
    }

    @Operation(operationId = "setDeliveryRadiusCap", summary = "Đặt bán kính giao tối đa", description = "Áp dụng cho các lần lưu sau; quán đang vượt mức giữ nguyên đến khi tự sửa.")
    @PutMapping("/settings/delivery-radius-cap")
    @PreAuthorize("hasAuthority('merchant-approval:write-settings')")
    ReviewDtos.RadiusCap setRadiusCap(CurrentPrincipal principal, @Valid @RequestBody ReviewDtos.RadiusCap request) {
        return reviews.setRadiusCap(principal, request.maxRadiusKm());
    }
}
