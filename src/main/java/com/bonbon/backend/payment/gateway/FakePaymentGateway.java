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
}
