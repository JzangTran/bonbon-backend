package com.bonbon.backend.account;

import java.util.Optional;
import java.util.UUID;

/** What other modules may read about a customer's saved delivery addresses. */
public interface DeliveryAddresses {

    /** The caller's own address as it is right now, ready to be copied into an order; empty when it is not theirs. */
    Optional<Snapshot> snapshotFor(UUID customerId, UUID addressId);

    /** {@code address} is the detail line (building, floor, flat) followed by the geocoded address. */
    record Snapshot(String recipientName, String recipientPhone, String address, double lat, double lng) {
    }
}
