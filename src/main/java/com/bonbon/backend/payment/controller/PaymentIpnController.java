package com.bonbon.backend.payment.controller;

import com.bonbon.backend.common.openapi.ApiError;
import com.bonbon.backend.common.openapi.ApiTags;
import com.bonbon.backend.payment.dto.MomoIpn;
import com.bonbon.backend.payment.service.PaymentIpnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Where MoMo reports the result of a payment. Not for the apps: no token, trust comes from the signature. */
@Tag(name = ApiTags.PAYMENT_WEBHOOK, description = "Địa chỉ nhận thông báo từ cổng thanh toán MoMo. Chỉ MoMo gọi, ứng dụng không dùng.")
@RestController
@RequestMapping("/api/payments")
class PaymentIpnController {

    private final PaymentIpnService ipn;

    PaymentIpnController(PaymentIpnService ipn) {
        this.ipn = ipn;
    }

    @Operation(operationId = "receiveMomoIpn", summary = "Nhận thông báo thanh toán MoMo (IPN)",
            description = "MoMo gọi sau khi khách thanh toán. Máy chủ kiểm tra chữ ký HMAC-SHA256, mã đối tác và số tiền so với đơn của mình rồi mới ghi nhận; thông báo trùng không làm gì thêm. Thanh toán thành công thì đơn từ `PENDING_PAYMENT` sang `PLACED`; nếu đơn đã bị huỷ (thanh toán đến muộn) thì tạo yêu cầu hoàn tiền. Trả 204 trong 15 giây.")
    @ApiError(status = 400, code = "INVALID_SIGNATURE", when = "Chữ ký không khớp.")
    @ApiError(status = 400, code = "INVALID_PARTNER", when = "Mã đối tác không phải của bonbon.")
    @ApiError(status = 400, code = "PAYMENT_NOT_FOUND", when = "Không có giao dịch với `orderId` này.")
    @ApiError(status = 400, code = "AMOUNT_MISMATCH", when = "Số tiền trong thông báo khác số tiền của đơn.")
    @ApiError(status = 503, code = "PAYMENTS_NOT_CONFIGURED", when = "Máy chủ chưa có khoá MoMo.")
    @ApiResponse(responseCode = "204", description = "Đã nhận.")
    @PostMapping("/momo/ipn")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(@RequestBody MomoIpn body) {
        ipn.handle(body);
    }
}
