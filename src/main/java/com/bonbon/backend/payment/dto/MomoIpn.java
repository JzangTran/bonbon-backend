package com.bonbon.backend.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The server-to-server notification MoMo posts after a payment (IPN, API v3). Every field takes part in the signature. */
@Schema(name = "MomoIpn", description = "Thông báo thanh toán (IPN) do MoMo gửi từ máy chủ tới máy chủ, ký bằng HMAC-SHA256.")
public record MomoIpn(
        String partnerCode,
        String orderId,
        String requestId,
        Long amount,
        String orderInfo,
        String orderType,
        Long transId,
        Integer resultCode,
        String message,
        String payType,
        Long responseTime,
        String extraData,
        String signature) {
}
