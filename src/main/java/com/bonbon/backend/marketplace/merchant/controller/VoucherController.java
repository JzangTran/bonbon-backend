package com.bonbon.backend.marketplace.merchant.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.dto.VoucherViews;
import com.bonbon.backend.marketplace.merchant.service.VoucherManagement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The seller's vouchers: created, edited, switched off, never deleted, since past orders refer to them. */
@Tag(name = ApiTags.SELLER_VOUCHERS, description = "Voucher của quán người gọi. Quán chịu tiền giảm; hoa hồng tính trên giá trị món sau giảm.")
@RestController
@RequestMapping("/api/merchant/vouchers")
class VoucherController {

    private final VoucherManagement vouchers;

    VoucherController(VoucherManagement vouchers) {
        this.vouchers = vouchers;
    }

    @Operation(operationId = "listMyVouchers", summary = "Voucher của quán", description = "Mới tạo trước, gồm cả voucher đã tắt, kèm số lượt đã dùng.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping
    @PreAuthorize("hasAuthority('voucher:read')")
    List<VoucherViews.Item> list(CurrentPrincipal principal) {
        return vouchers.list(principal);
    }

    @Operation(operationId = "createVoucher", summary = "Tạo voucher", description = "Mã không trùng trong cùng quán, không phân biệt hoa thường. `PERCENT`: `discountValue` là phần trăm, `maxDiscount` là mức giảm tối đa. `FIXED`: `discountValue` là số tiền.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 409, code = "VOUCHER_CODE_TAKEN", when = "Quán đã có mã này.")
    @ApiError(status = 400, code = "VOUCHER_WINDOW_INVALID", when = "Ngày kết thúc không sau ngày bắt đầu.")
    @ApiError(status = 400, code = "VOUCHER_PERCENT_INVALID", when = "Mức giảm phần trăm trên 100%.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('voucher:write')")
    VoucherViews.Item create(CurrentPrincipal principal, @Valid @RequestBody VoucherViews.Create request) {
        return vouchers.create(principal, request);
    }

    @Operation(operationId = "updateVoucher", summary = "Sửa voucher", description = "Chỉ trường gửi lên mới đổi; mã không đổi được. Đơn đã đặt giữ nguyên mức giảm cũ.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "VOUCHER_NOT_FOUND", when = "Voucher không tồn tại hoặc thuộc quán khác.")
    @ApiError(status = 400, code = "VOUCHER_WINDOW_INVALID", when = "Ngày kết thúc không sau ngày bắt đầu.")
    @ApiError(status = 400, code = "VOUCHER_PERCENT_INVALID", when = "Mức giảm phần trăm trên 100%.")
    @ApiError(status = 400, code = "VOUCHER_LIMIT_BELOW_USED", when = "Giới hạn thấp hơn số lượt đã dùng.")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('voucher:write')")
    VoucherViews.Item update(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody VoucherViews.Update request) {
        return vouchers.update(principal, id, request);
    }

    @Operation(operationId = "setVoucherStatus", summary = "Bật hoặc tắt voucher", description = "Voucher đã tắt không dùng được cho đơn mới; đơn đã đặt không bị ảnh hưởng.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @ApiError(status = 404, code = "VOUCHER_NOT_FOUND", when = "Voucher không tồn tại hoặc thuộc quán khác.")
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('voucher:write')")
    VoucherViews.Item setStatus(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody VoucherViews.Status request) {
        return vouchers.setStatus(principal, id, request.status());
    }
}
