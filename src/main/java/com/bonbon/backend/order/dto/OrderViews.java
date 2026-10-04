package com.bonbon.backend.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.order.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a customer sees of an order: the snapshot taken when it was placed, plus its timeline. */
public final class OrderViews {

    private OrderViews() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "OrderDetail")
    public record Detail(
            UUID id,
            long number,
            OrderStatus status,
            String paymentMethod,
            String paymentStatus,
            Shop shop,
            Delivery delivery,
            List<Line> items,
            Totals totals,
            Instant placedAt,
            List<Step> timeline) {
    }

    @Schema(name = "OrderShop")
    public record Shop(UUID id, String name) {
    }

    @Schema(name = "OrderDelivery")
    public record Delivery(String name, String phone, String address, String note) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "OrderLine")
    public record Line(UUID menuItemId, String name, int quantity, int unitPrice, int lineTotal, String note, List<Option> options) {
    }

    @Schema(name = "OrderLineOption")
    public record Option(String group, String name, int priceDelta) {
    }

    @Schema(name = "OrderTotals")
    public record Totals(int itemsTotal, int discount, int deliveryFee, int grandTotal) {
    }

    /** {@code by} is CUSTOMER, SHOP, SYSTEM or ADMIN: the shop's own staff are never named to the customer. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "OrderStep")
    public record Step(OrderStatus from, OrderStatus to, String by, String reason, Instant at) {
    }

    @Schema(name = "OrderSummary")
    public record Summary(UUID id, long number, OrderStatus status, String shopName, int grandTotal, int itemCount,
            String itemsPreview, Instant placedAt) {
    }

    @Schema(name = "OrderPage")
    public record Page(List<Summary> items, int page, int size, long total) {
    }
}
