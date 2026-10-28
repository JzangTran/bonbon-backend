package com.bonbon.backend.payments.payment.gateway;

/** MoMo credentials and addresses; the keys come from the environment only. */
public record MomoSettings(String endpoint, String partnerCode, String accessKey, String secretKey, String redirectUrl, String ipnUrl) {
}
