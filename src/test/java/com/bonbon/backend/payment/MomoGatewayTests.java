package com.bonbon.backend.payment;

import java.util.Map;

import com.bonbon.backend.payment.gateway.MomoPaymentGateway;
import com.bonbon.backend.payment.gateway.MomoSettings;
import com.bonbon.backend.payment.gateway.MomoSigner;
import com.bonbon.backend.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The MoMo signature rule and the two API calls, without the network. */
class MomoGatewayTests {

    private static final MomoSettings SETTINGS = new MomoSettings("https://test-payment.momo.vn", "PARTNER", "ACCESS", "SECRET",
            "https://app.example/payment/return", "https://api.example/api/payments/momo/ipn");

    @Test
    void hmacSha256MatchesTheRfc4231Vector() {
        // RFC 4231, test case 2.
        assertThat(MomoSigner.hmacSha256Hex("Jefe", "what do ya want for nothing?"))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void theSignedStringIsAlphabeticalWithTheAccessKeyIncluded() {
        MomoSigner signer = new MomoSigner("ACCESS", "SECRET");
        String raw = signer.raw(Map.of("orderId", "o1", "partnerCode", "P", "requestId", "r1"));
        assertThat(raw).isEqualTo("accessKey=ACCESS&orderId=o1&partnerCode=P&requestId=r1");
        assertThat(signer.sign(Map.of("orderId", "o1", "partnerCode", "P", "requestId", "r1")))
                .isEqualTo(MomoSigner.hmacSha256Hex("SECRET", raw));
        // Null values are signed as empty text, the way MoMo does for an empty extraData.
        assertThat(signer.raw(Map.of("b", "2")).replace("accessKey=ACCESS&", "")).isEqualTo("b=2");
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("extraData", null);
        assertThat(signer.raw(withNull)).isEqualTo("accessKey=ACCESS&extraData=");
    }

    @Test
    void aSignatureOnlyMatchesItsOwnFieldsAndKeys() {
        MomoSigner signer = new MomoSigner("ACCESS", "SECRET");
        Map<String, Object> fields = Map.of("amount", 1000, "orderId", "o1");
        String signature = signer.sign(fields);
        assertThat(signer.matches(fields, signature)).isTrue();
        assertThat(signer.matches(fields, signature.toUpperCase())).isTrue();
        assertThat(signer.matches(Map.of("amount", 1001, "orderId", "o1"), signature)).isFalse();
        assertThat(new MomoSigner("ACCESS", "OTHER").matches(fields, signature)).isFalse();
        assertThat(signer.matches(fields, null)).isFalse();
        assertThat(new MomoSigner("", "").matches(fields, signature)).isFalse();
    }

    @Test
    void createPostsTheSignedRequestAndReadsTheLinks() {
        RestClient.Builder builder = RestClient.builder().baseUrl(SETTINGS.endpoint());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        MomoSigner signer = new MomoSigner(SETTINGS.accessKey(), SETTINGS.secretKey());
        PaymentGateway gateway = new MomoPaymentGateway(builder.build(), SETTINGS, signer);

        server.expect(requestTo("https://test-payment.momo.vn/v2/gateway/api/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.partnerCode").value("PARTNER"))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.requestType").value("captureWallet"))
                .andExpect(jsonPath("$.ipnUrl").value(SETTINGS.ipnUrl()))
                .andExpect(request -> {
                    Map<?, ?> body = JsonMapper.builder().build().readValue(request.getBody().toString(), Map.class);
                    Map<String, Object> signed = new java.util.LinkedHashMap<>();
                    for (String key : new String[] {"partnerCode", "requestId", "amount", "orderId", "orderInfo", "redirectUrl", "ipnUrl", "requestType", "extraData"}) {
                        signed.put(key, body.get(key));
                    }
                    assertThat(body.get("signature")).isEqualTo(signer.sign(signed));
                })
                .andRespond(withSuccess("{\"resultCode\":0,\"message\":\"Successful.\",\"payUrl\":\"https://pay\",\"deeplink\":\"momo://x\",\"qrCodeUrl\":\"momo://qr\"}",
                        MediaType.APPLICATION_JSON));

        PaymentGateway.CreateResult result = gateway.create(new PaymentGateway.CreateRequest("order-1", "req-1", 50000, "Thanh toan don #1"));

        assertThat(result.ok()).isTrue();
        assertThat(result.payUrl()).isEqualTo("https://pay");
        assertThat(result.deeplink()).isEqualTo("momo://x");
        assertThat(result.qrCodeUrl()).isEqualTo("momo://qr");
        server.verify();
    }

    @Test
    void aRefusedCreateKeepsMomosCode() {
        RestClient.Builder builder = RestClient.builder().baseUrl(SETTINGS.endpoint());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaymentGateway gateway = new MomoPaymentGateway(builder.build(), SETTINGS, new MomoSigner("ACCESS", "SECRET"));
        server.expect(requestTo("https://test-payment.momo.vn/v2/gateway/api/create"))
                .andRespond(withSuccess("{\"resultCode\":41,\"message\":\"orderId is duplicated\"}", MediaType.APPLICATION_JSON));

        PaymentGateway.CreateResult result = gateway.create(new PaymentGateway.CreateRequest("order-1", "req-1", 50000, "x"));

        assertThat(result.ok()).isFalse();
        assertThat(result.resultCode()).isEqualTo(41);
        assertThat(result.payUrl()).isNull();
    }

    @Test
    void queryAsksByOrderIdAndReadsTheOutcome() {
        RestClient.Builder builder = RestClient.builder().baseUrl(SETTINGS.endpoint());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PaymentGateway gateway = new MomoPaymentGateway(builder.build(), SETTINGS, new MomoSigner("ACCESS", "SECRET"));
        server.expect(requestTo("https://test-payment.momo.vn/v2/gateway/api/query"))
                .andExpect(jsonPath("$.orderId").value("order-1"))
                .andExpect(jsonPath("$.signature").isNotEmpty())
                .andRespond(withSuccess("{\"resultCode\":0,\"transId\":2820,\"amount\":50000,\"payType\":\"qr\",\"message\":\"Successful.\"}",
                        MediaType.APPLICATION_JSON));

        PaymentGateway.QueryResult result = gateway.query("order-1");

        assertThat(result.resultCode()).isZero();
        assertThat(result.transId()).isEqualTo(2820L);
        assertThat(result.amount()).isEqualTo(50000L);
        assertThat(result.payType()).isEqualTo("qr");
    }
}
