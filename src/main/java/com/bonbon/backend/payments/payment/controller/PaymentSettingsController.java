package com.bonbon.backend.payments.payment.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.payments.payment.dto.PaymentSettingsViews;
import com.bonbon.backend.payments.payment.service.PaymentSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Cài đặt thanh toán: the wallets a customer has linked, the main one, and the payment PIN. */
@Tag(name = ApiTags.CUSTOMER_PAYMENTS, description = "Phương thức thanh toán đã liên kết và mã PIN thanh toán của khách.")
@RestController
@RequestMapping("/api/account")
@PreAuthorize("hasAuthority('order:create')")
class PaymentSettingsController {

    private final PaymentSettingsService settings;

    PaymentSettingsController(PaymentSettingsService settings) {
        this.settings = settings;
    }

    @Operation(operationId = "listPaymentMethods", summary = "Phương thức thanh toán đã liên kết", description = "Phương thức chính trước. `linkable` là các phương thức còn liên kết được.")
    @GetMapping("/payment-methods")
    PaymentSettingsViews.Methods methods(CurrentPrincipal principal) {
        return settings.methods(principal.id());
    }

    @Operation(operationId = "linkPaymentMethod", summary = "Liên kết phương thức thanh toán", description = "Chỉ lưu 4 số cuối của tài khoản. Phương thức đầu tiên tự thành phương thức chính. Tiền vẫn trả qua cổng của nhà cung cấp khi đặt đơn.")
    @ApiError(status = 400, code = "UNSUPPORTED_PAYMENT_PROVIDER", when = "Nhà cung cấp chưa được hỗ trợ (hiện có MOMO).")
    @ApiError(status = 400, code = "VALIDATION_ERROR", when = "Số điện thoại ví không hợp lệ.")
    @ApiError(status = 409, code = "PAYMENT_METHOD_ALREADY_LINKED", when = "Đã liên kết phương thức này.")
    @PostMapping("/payment-methods")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentSettingsViews.Method link(CurrentPrincipal principal, @Valid @RequestBody PaymentSettingsViews.Link request) {
        return settings.link(principal.id(), request);
    }

    @Operation(operationId = "setPrimaryPaymentMethod", summary = "Đặt làm phương thức thanh toán chính", description = "Bật cho một phương thức thì tắt ở các phương thức khác.")
    @ApiError(status = 404, code = "PAYMENT_METHOD_NOT_FOUND", when = "Không có phương thức này trong tài khoản.")
    @PatchMapping("/payment-methods/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPrimary(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody PaymentSettingsViews.Primary request) {
        settings.setPrimary(principal.id(), id, request.primary());
    }

    @Operation(operationId = "unlinkPaymentMethod", summary = "Gỡ liên kết phương thức thanh toán", description = "Xoá hẳn phương thức khỏi tài khoản.")
    @ApiError(status = 404, code = "PAYMENT_METHOD_NOT_FOUND", when = "Không có phương thức này trong tài khoản.")
    @DeleteMapping("/payment-methods/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlink(CurrentPrincipal principal, @PathVariable UUID id) {
        settings.unlink(principal.id(), id);
    }

    @Operation(operationId = "getPaymentPin", summary = "Đã có mã PIN thanh toán chưa", description = "Chỉ cho biết đã đặt hay chưa, không bao giờ trả mã.")
    @GetMapping("/payment-pin")
    PaymentSettingsViews.PinStatus pin(CurrentPrincipal principal) {
        return settings.pin(principal.id());
    }

    @Operation(operationId = "changePaymentPin", summary = "Tạo hoặc đổi mã PIN thanh toán", description = "Mã 6 chữ số. Đã có mã thì phải gửi kèm `currentPin`; sai 5 lần thì khoá đổi mã 15 phút.")
    @ApiError(status = 400, code = "VALIDATION_ERROR", when = "Mã PIN không đủ 6 chữ số.")
    @ApiError(status = 422, code = "PIN_INCORRECT", when = "Mã PIN hiện tại không đúng.")
    @ApiError(status = 429, code = "PIN_LOCKED", when = "Nhập sai mã PIN quá 5 lần; thử lại sau 15 phút.")
    @PutMapping("/payment-pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePin(CurrentPrincipal principal, @Valid @RequestBody PaymentSettingsViews.PinChange request) {
        settings.changePin(principal.id(), request);
    }
}
