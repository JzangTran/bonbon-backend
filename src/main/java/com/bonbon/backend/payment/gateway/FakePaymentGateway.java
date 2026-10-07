package com.bonbon.backend.payment.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The default provider: creates a payment that nobody can really pay, and answers queries from what a test or the
 * dev simulator told it. Lets everything around MoMo run (and be tested) without the network.
 */
public class FakePaymentGateway implements PaymentGateway {

    private final Map<String, QueryResult> results = new ConcurrentHashMap<>();
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final String baseUrl;
    private final Map<String, RefundResult> refundAnswers = new ConcurrentHashMap<>();
    private final java.util.List<RefundRequest> refundCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile RefundResult refundDefault = null;

    /** {@code baseUrl} is where this backend can be reached: the dev payment page it serves lives there. */
    public FakePaymentGateway(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public CreateResult create(CreateRequest request) {
        if (failNext.getAndSet(false)) {
            return new CreateResult(99, "Fake gateway refused the payment", null, null, null, "{fake}");
        }
        String id = request.providerOrderId();
        return new CreateResult(0, "Successful.", baseUrl + "/api/payments/fake/" + id, "momo://fake-pay?orderId=" + id,
                baseUrl + "/api/payments/fake/" + id, "{fake}");
    }

    @Override
    public QueryResult query(String providerOrderId) {
        // 1000 = initiated and waiting, in MoMo's own code list.
        return results.getOrDefault(providerOrderId, new QueryResult(1000, 0, 0, null, "Waiting"));
    }

    /** What the next queries of this payment answer. */
    public void answer(String providerOrderId, QueryResult result) {
        results.put(providerOrderId, result);
    }

    /** The next create call is refused, as MoMo would on a bad request. */
    public void failNextCreate() {
        failNext.set(true);
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        refundCalls.add(request);
        RefundResult answer = refundAnswers.getOrDefault(request.providerOrderId(), refundDefault);
        if (answer != null) {
            return answer;
        }
        return new RefundResult(0, Math.abs(request.providerOrderId().hashCode()) + 1_000_000L, "Successful.");
    }

    /** Every refund call so far, oldest first. */
    public java.util.List<RefundRequest> refundCalls() {
        return refundCalls;
    }

    /** What refunds answer from now on unless a refund has its own answer; {@code null} restores success. */
    public void answerRefunds(RefundResult result) {
        refundDefault = result;
    }

    public void answerRefund(String providerOrderId, RefundResult result) {
        refundAnswers.put(providerOrderId, result);
    }
}
