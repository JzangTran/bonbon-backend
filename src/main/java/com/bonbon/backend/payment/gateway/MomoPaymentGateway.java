package com.bonbon.backend.payment.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * MoMo's payment API v3 over HTTP: {@code POST /v2/gateway/api/create} and {@code /query}, signed with
 * {@link MomoSigner}. The client's own timeout (30 s, MoMo's stated minimum) is set where the RestClient is built.
 * A transport failure is thrown; a refusal from MoMo comes back as a non-zero {@code resultCode}.
 */
public class MomoPaymentGateway implements PaymentGateway {

    private static final String REQUEST_TYPE = "captureWallet";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    private final RestClient client;
    private final MomoSettings settings;
    private final MomoSigner signer;

    public MomoPaymentGateway(RestClient client, MomoSettings settings, MomoSigner signer) {
        this.client = client;
        this.settings = settings;
        this.signer = signer;
    }

    @Override
    public CreateResult create(CreateRequest request) {
        // The signature covers exactly these fields; "lang" and the signature itself are not part of it.
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("partnerCode", settings.partnerCode());
        signed.put("requestId", request.requestId());
        signed.put("amount", request.amount());
        signed.put("orderId", request.providerOrderId());
        signed.put("orderInfo", request.orderInfo());
        signed.put("redirectUrl", settings.redirectUrl());
        signed.put("ipnUrl", settings.ipnUrl());
        signed.put("requestType", REQUEST_TYPE);
        signed.put("extraData", "");
        Map<String, Object> body = new LinkedHashMap<>(signed);
        body.put("lang", "vi");
        body.put("signature", signer.sign(signed));

        Map<String, Object> response = client.post().uri("/v2/gateway/api/create").body(body).retrieve().body(JSON);
        if (response == null) {
            throw new IllegalStateException("Empty answer from MoMo");
        }
        return new CreateResult(intOf(response.get("resultCode")), text(response.get("message")), text(response.get("payUrl")),
                text(response.get("deeplink")), text(response.get("qrCodeUrl")), response.toString());
    }

    @Override
    public QueryResult query(String providerOrderId) {
        Map<String, Object> signed = new LinkedHashMap<>();
        signed.put("partnerCode", settings.partnerCode());
        signed.put("requestId", UUID.randomUUID().toString());
        signed.put("orderId", providerOrderId);
        Map<String, Object> body = new LinkedHashMap<>(signed);
        body.put("lang", "vi");
        body.put("signature", signer.sign(signed));

        Map<String, Object> response = client.post().uri("/v2/gateway/api/query").body(body).retrieve().body(JSON);
        if (response == null) {
            throw new IllegalStateException("Empty answer from MoMo");
        }
        return new QueryResult(intOf(response.get("resultCode")), longOf(response.get("transId")), longOf(response.get("amount")),
                text(response.get("payType")), text(response.get("message")));
    }

    private static int intOf(Object value) {
        return (int) longOf(value);
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : value == null ? 0 : Long.parseLong(value.toString());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
