package com.bonbon.backend.order.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Requests of a customer placing an order (place-order.md). There are no prices here: the server prices everything. */
public final class OrderRequests {

    private OrderRequests() {
    }

    /** {@code paymentMethod}: only {@code COD} exists until online payment is built. */
    @Schema(name = "PlaceOrderRequest")
    public record Place(
            @NotNull UUID vendorId,
            @NotNull UUID addressId,
            @NotNull @Pattern(regexp = "COD", message = "Hiện chỉ hỗ trợ thanh toán khi nhận hàng.") String paymentMethod,
            @Size(max = 300) String note,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull Line> items) {
    }

    @Schema(name = "PlaceOrderLine")
    public record Line(
            @NotNull UUID menuItemId,
            @NotNull @Min(1) @Max(99) Integer quantity,
            @Size(max = 30) List<@NotNull UUID> optionIds,
            @Size(max = 200) String note) {
    }

    @Schema(name = "CancelOrderRequest")
    public record Cancel(@Size(max = 300) String reason) {
    }
}
