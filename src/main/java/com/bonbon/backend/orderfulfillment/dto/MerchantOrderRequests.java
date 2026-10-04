package com.bonbon.backend.orderfulfillment.dto;

import com.bonbon.backend.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Requests of a shop moving an order along (confirm-order.md, update-order-status.md). */
public final class MerchantOrderRequests {

    private MerchantOrderRequests() {
    }

    /** Required when rejecting a new order or cancelling after confirming. */
    @Schema(name = "OrderReasonRequest")
    public record Reason(@Size(max = 300) String reason) {
    }

    /** The next step: PREPARING, OUT_FOR_DELIVERY or DELIVERED. */
    @Schema(name = "OrderStepRequest")
    public record Step(@NotNull OrderStatus to) {
    }
}
