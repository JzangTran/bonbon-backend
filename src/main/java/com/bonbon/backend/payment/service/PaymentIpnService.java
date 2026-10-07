package com.bonbon.backend.payment.service;

import java.util.LinkedHashMap;
import java.util.Map;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.payment.dto.MomoIpn;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The MoMo IPN (pay-online.md): the only message, besides our own reconciliation query, that may change a payment.
 * Checks the signature, the partner code and the amount against our own records before anything is written; the
 * customer coming back to the redirect URL is never trusted. The handler does database work only.
 */
@Service
public class PaymentIpnService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIpnService.class);

    private final MomoSigner signer;
    private final MomoSettings settings;
    private final PaymentOutcomes outcomes;

    PaymentIpnService(MomoSigner signer, MomoSettings settings, PaymentOutcomes outcomes) {
        this.signer = signer;
        this.settings = settings;
        this.outcomes = outcomes;
    }

    @Transactional
    public void handle(MomoIpn ipn) {
        if (!signer.configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED", "Thanh toán trực tuyến chưa được cấu hình.");
        }
        if (!signer.matches(signedFields(ipn), ipn.signature())) {
            log.warn("IPN with a wrong signature for {}", ipn.orderId());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE", "Chữ ký không hợp lệ.");
        }
        if (!settings.partnerCode().equals(ipn.partnerCode())) {
            log.warn("IPN for another partner code on {}", ipn.orderId());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PARTNER", "Mã đối tác không khớp.");
        }
        PaymentOutcomes.Target target = outcomes.lock(ipn.orderId())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch."));
        if (ipn.amount() == null || ipn.amount() != target.amount()) {
            log.warn("IPN amount {} differs from the payment amount {} for {}", ipn.amount(), target.amount(), ipn.orderId());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "AMOUNT_MISMATCH", "Số tiền không khớp.");
        }
        outcomes.apply(target, ipn.resultCode() == null ? -1 : ipn.resultCode(), ipn.transId() == null ? 0 : ipn.transId(), ipn.payType());
    }

    /** Every field of the notification except the signature takes part in it, in MoMo's documented set. */
    static Map<String, Object> signedFields(MomoIpn ipn) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("amount", ipn.amount());
        fields.put("extraData", ipn.extraData());
        fields.put("message", ipn.message());
        fields.put("orderId", ipn.orderId());
        fields.put("orderInfo", ipn.orderInfo());
        fields.put("orderType", ipn.orderType());
        fields.put("partnerCode", ipn.partnerCode());
        fields.put("payType", ipn.payType());
        fields.put("requestId", ipn.requestId());
        fields.put("responseTime", ipn.responseTime());
        fields.put("resultCode", ipn.resultCode());
        fields.put("transId", ipn.transId());
        return fields;
    }
}
