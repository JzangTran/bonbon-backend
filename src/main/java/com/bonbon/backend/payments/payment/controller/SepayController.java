package com.bonbon.backend.payments.payment.controller;

import java.util.UUID;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.payments.payment.dto.SepayViews;
import com.bonbon.backend.payments.payment.service.SepayService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Automatic transfer confirmation: the shop's SePay connection, and the webhook SePay calls for each transaction. */
@RestController
class SepayController {

    private final SepayService sepay;

    SepayController(SepayService sepay) {
        this.sepay = sepay;
    }

    @Tag(name = ApiTags.SELLER_ORDERS)
    @Operation(operationId = "getSepayConnection", summary = "Trạng thái xác nhận chuyển khoản tự động",
            description = "Đã kết nối SePay chưa, địa chỉ webhook để dán vào SePay, lần cuối SePay gọi và mười giao dịch gần nhất kèm kết quả khớp đơn.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @GetMapping("/api/merchant/payments/sepay")
    @PreAuthorize("hasAuthority('vendor:read')")
    SepayViews.Status status(CurrentPrincipal principal) {
        return sepay.status(principal);
    }

    @Tag(name = ApiTags.SELLER_ORDERS)
    @Operation(operationId = "connectSepay", summary = "Tạo khoá webhook SePay",
            description = "Tạo mới hoặc đổi khoá; khoá cũ hết hiệu lực ngay. Khoá chỉ hiện một lần. Trong SePay chọn kiểu chứng thực API Key và dán khoá này.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @PostMapping("/api/merchant/payments/sepay")
    @PreAuthorize("hasAuthority('vendor:write')")
    @ResponseStatus(HttpStatus.CREATED)
    SepayViews.Connection connect(CurrentPrincipal principal) {
        return sepay.connect(principal);
    }

    @Tag(name = ApiTags.SELLER_ORDERS)
    @Operation(operationId = "setSepaySubAccount", summary = "Đặt mã tài khoản ảo SePay",
            description = "Với ngân hàng mà SePay chỉ báo giao dịch có nội dung bắt đầu bằng `SEVQR TKP<mã VA>` (ví dụ VietinBank cá nhân), quán nhập mã VA SePay cấp; từ đó nội dung VietQR của đơn là `SEVQR TKP<mã> BONBON<số đơn>`. Để trống để bỏ.")
    @ApiError(status = 400, code = "VALIDATION_FAILED", when = "Mã không phải 1–10 chữ cái hoặc chữ số.")
    @ApiError(status = 409, code = "SEPAY_NOT_CONNECTED", when = "Quán chưa kết nối SePay.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @PutMapping("/api/merchant/payments/sepay/sub-account")
    @PreAuthorize("hasAuthority('vendor:write')")
    SepayViews.Status setSubAccount(CurrentPrincipal principal, @Valid @RequestBody SepayViews.SubAccount body) {
        return sepay.setSubAccount(principal, body.code());
    }

    @Tag(name = ApiTags.SELLER_ORDERS)
    @Operation(operationId = "disconnectSepay", summary = "Ngắt xác nhận chuyển khoản tự động", description = "Webhook bị từ chối từ lúc này; quán vẫn tự xác nhận được từng đơn.")
    @ApiError(status = 409, code = "SHOP_NOT_APPROVED", when = "Người gọi chưa có cửa hàng được duyệt.")
    @DeleteMapping("/api/merchant/payments/sepay")
    @PreAuthorize("hasAuthority('vendor:write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(CurrentPrincipal principal) {
        sepay.disconnect(principal);
    }

    @Tag(name = ApiTags.PAYMENT_WEBHOOK)
    @Operation(operationId = "sepayWebhook", summary = "SePay báo có giao dịch",
            description = "SePay gọi máy chủ với máy chủ, header `Authorization: Apikey <khoá>`. Nội dung có `BONBON<số đơn>` và đủ tiền thì đơn chuyển khoản đó thành đã thanh toán. Cùng một `id` chỉ xử lý một lần; giao dịch không khớp đơn vẫn trả `success: true` để SePay thôi gửi lại.")
    @ApiError(status = 401, code = "SEPAY_UNAUTHORIZED", when = "Thiếu hoặc sai khoá, hoặc quán chưa kết nối.")
    @ApiError(status = 400, code = "SEPAY_PAYLOAD_INVALID", when = "Thiếu `id` hoặc `transferAmount`.")
    @PostMapping("/api/payments/sepay/{vendorId}")
    SepayViews.Ack webhook(@PathVariable UUID vendorId, @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) SepayViews.Webhook body) {
        return sepay.receive(vendorId, authorization, body);
    }
}
