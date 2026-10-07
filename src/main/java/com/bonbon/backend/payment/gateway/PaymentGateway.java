package com.bonbon.backend.payment.gateway;

/** The payment provider behind online payments (MoMo today); swapping it must not touch order or settlement code. */
public interface PaymentGateway {

    /** Asks the provider for a payment the customer can complete; may take up to 30 s. */
    CreateResult create(CreateRequest request);

    /** Asks the provider how a payment ended; the way to learn the result when the IPN never came. */
    QueryResult query(String providerOrderId);

    /**
     * Gives money back through the provider. Synchronous: the answer says whether it is done. A transport failure is
     * thrown; a refusal comes back as a result code. Retrying with the same {@code providerOrderId} can never pay twice.
     */
    RefundResult refund(RefundRequest request);

    /** {@code providerOrderId} is sent as MoMo's orderId and must never repeat. */
    record CreateRequest(String providerOrderId, String requestId, int amount, String orderInfo) {
    }

    record CreateResult(int resultCode, String message, String payUrl, String deeplink, String qrCodeUrl, String raw) {

        public boolean ok() {
            return resultCode == 0;
        }
    }

    /**
     * {@code resultCode} is MoMo's; {@code transId} and {@code amount} are what the provider recorded. {@code refunds}
     * lists the refunds the provider holds against this payment.
     */
    record QueryResult(int resultCode, long transId, long amount, String payType, String message, java.util.List<RefundTrans> refunds) {

        public QueryResult(int resultCode, long transId, long amount, String payType, String message) {
            this(resultCode, transId, amount, payType, message, java.util.List.of());
        }
    }

    /** One refund as the provider lists it: {@code orderId} is the one we sent when asking for it. */
    record RefundTrans(String orderId, long transId, long amount, int resultCode) {
    }

    /** {@code purchaseTransId} is the provider's id of the payment being refunded. */
    record RefundRequest(String providerOrderId, String requestId, int amount, long purchaseTransId, String description) {
    }

    record RefundResult(int resultCode, long transId, String message) {
    }
}
