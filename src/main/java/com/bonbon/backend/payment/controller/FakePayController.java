package com.bonbon.backend.payment.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.payment.dto.MomoIpn;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.service.PaymentIpnService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in for the MoMo payment page while the fake gateway is on (development and demos without MoMo): a page with
 * "pay" and "decline" buttons, and the call behind them, which sends the same signed IPN MoMo would. Never exists in
 * production: {@code PaymentConfig} refuses the fake provider there and this controller needs the non-prod profile.
 */
@Hidden
@Profile("!prod")
@ConditionalOnProperty(name = "bonbon.payment.provider", havingValue = "fake", matchIfMissing = true)
@RestController
@RequestMapping("/api/payments/fake")
class FakePayController {

    private final PaymentIpnService ipn;
    private final MomoSigner signer;
    private final MomoSettings settings;
    private final JdbcClient jdbc;

    FakePayController(PaymentIpnService ipn, MomoSigner signer, MomoSettings settings, JdbcClient jdbc) {
        this.ipn = ipn;
        this.signer = signer;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/{providerOrderId}", produces = MediaType.TEXT_HTML_VALUE)
    String page(@PathVariable UUID providerOrderId) {
        return """
                <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>MoMo (giả lập)</title>
                <body style="font-family:system-ui;max-width:24rem;margin:3rem auto;padding:0 1rem;text-align:center">
                <h1>Thanh toán MoMo (giả lập)</h1>
                <p>Trang này chỉ có khi chạy thử, không có tiền thật.</p>
                <button style="padding:.8rem 2rem;font-size:1rem" onclick="go(0)">Thanh toán thành công</button>
                <p><button style="padding:.8rem 2rem;font-size:1rem" onclick="go(1006)">Từ chối thanh toán</button></p>
                <p id="r"></p>
                <script>
                async function go(code){
                  const res = await fetch('/api/payments/fake/%s/pay?resultCode='+code,{method:'POST'});
                  document.getElementById('r').textContent = res.ok ? 'Xong. Quay lại ứng dụng bonbon.' : 'Lỗi '+res.status;
                }
                </script>
                """.formatted(providerOrderId);
    }

    @PostMapping("/{providerOrderId}/pay")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pay(@PathVariable UUID providerOrderId, @RequestParam(defaultValue = "0") int resultCode) {
        long amount = jdbc.sql("""
                select p.amount from payment_attempts a join payments p on p.id = a.payment_id where a.provider_order_id = :po""")
                .param("po", providerOrderId.toString()).query(Long.class).optional()
                .orElseThrow(() -> BusinessException.notFound("PAYMENT_NOT_FOUND", "Không tìm thấy giao dịch."));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("amount", amount);
        fields.put("extraData", "");
        fields.put("message", resultCode == 0 ? "Successful." : "Declined");
        fields.put("orderId", providerOrderId.toString());
        fields.put("orderInfo", "fake");
        fields.put("orderType", "momo_wallet");
        fields.put("partnerCode", settings.partnerCode());
        fields.put("payType", "qr");
        fields.put("requestId", UUID.randomUUID().toString());
        fields.put("responseTime", System.currentTimeMillis());
        fields.put("resultCode", resultCode);
        fields.put("transId", System.nanoTime() / 1000);
        ipn.handle(new MomoIpn(settings.partnerCode(), providerOrderId.toString(), (String) fields.get("requestId"), amount, "fake",
                "momo_wallet", (Long) fields.get("transId"), resultCode, (String) fields.get("message"), "qr",
                (Long) fields.get("responseTime"), "", signer.sign(fields)));
    }
}
