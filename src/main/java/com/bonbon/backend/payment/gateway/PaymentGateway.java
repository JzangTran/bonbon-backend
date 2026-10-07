package com.bonbon.backend.payment.gateway;

/** The payment provider behind online payments (MoMo today); swapping it must not touch order or settlement code. */
public interface PaymentGateway {

    /** Asks the provider for a payment the customer can complete; may take up to 30 s. */
    CreateResult create(CreateRequest request);

    /** Asks the provider how a payment ended; the way to learn the result when the IPN never came. */
    QueryResult query(String providerOrderId);

    /** {@code providerOrderId} is sent as MoMo's orderId and must never repeat. */
    record CreateRequest(String providerOrderId, String requestId, int amount, String orderInfo) {
    }

    record CreateResult(int resultCode, String message, String payUrl, String deeplink, String qrCodeUrl, String raw) {

        public boolean ok() {
            return resultCode == 0;
        }
    }

    /** {@code resultCode} is MoMo's; {@code transId} and {@code amount} are what the provider recorded. */
    record QueryResult(int resultCode, long transId, long amount, String payType, String message) {
    }
}
