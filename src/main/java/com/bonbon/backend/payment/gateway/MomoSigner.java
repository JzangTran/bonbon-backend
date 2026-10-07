package com.bonbon.backend.payment.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * MoMo's request and notification signature: HMAC-SHA256 with the secret key over {@code key=value} pairs joined by
 * {@code &}, in alphabetical key order, with the access key among them. The same rule signs create, query, refund
 * and IPN; only the listed fields differ.
 */
public final class MomoSigner {

    private final String accessKey;
    private final String secretKey;

    public MomoSigner(String accessKey, String secretKey) {
        this.accessKey = accessKey == null ? "" : accessKey;
        this.secretKey = secretKey == null ? "" : secretKey;
    }

    /** Signing needs both keys; without them no payment can be created and no IPN can be trusted. */
    public boolean configured() {
        return !accessKey.isBlank() && !secretKey.isBlank();
    }

    /** The string that is signed: the fields plus {@code accessKey}, alphabetically, nulls as empty text. */
    public String raw(Map<String, ?> fields) {
        Map<String, String> sorted = new TreeMap<>();
        fields.forEach((k, v) -> sorted.put(k, v == null ? "" : String.valueOf(v)));
        sorted.put("accessKey", accessKey);
        return sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    public String sign(Map<String, ?> fields) {
        return hmacSha256Hex(secretKey, raw(fields));
    }

    /** Constant-time comparison, so a wrong signature leaks nothing about the right one. */
    public boolean matches(Map<String, ?> fields, String signature) {
        if (!configured() || signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(fields).getBytes(StandardCharsets.UTF_8), signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
