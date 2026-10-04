package com.bonbon.backend.orderfulfillment.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import com.bonbon.backend.orderfulfillment.dto.MerchantOrderRequests;
import com.bonbon.backend.orderfulfillment.service.MerchantOrderService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own shop's orders; an order of another shop is simply not found. */
@RestController
@RequestMapping("/api/merchant/orders")
class MerchantOrderController {

    private final MerchantOrderService orders;

    MerchantOrderController(MerchantOrderService orders) {
        this.orders = orders;
    }

    /**
     * {@code status} may be repeated (empty means all); {@code from} and {@code to} are Vietnam-time dates, both
     * inclusive; {@code sort} is {@code newest} (default) or {@code oldest} (the new-orders queue).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    ShopOrders.ShopOrderPage list(CurrentPrincipal principal, @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "newest") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return orders.list(principal, status, from, to, "oldest".equals(sort), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:read')")
    ShopOrders.ShopOrderDetail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.get(principal, id);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail confirm(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.move(principal, id, OrderStatus.CONFIRMED, null);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail reject(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Reason request) {
        return orders.move(principal, id, OrderStatus.REJECTED, request.reason());
    }

    /** One step at a time: CONFIRMED to PREPARING, PREPARING to OUT_FOR_DELIVERY, then DELIVERED. */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail status(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Step request) {
        return orders.move(principal, id, request.to(), null);
    }

    /** After confirming, the shop may still cancel (out of an ingredient); a reason is required and it counts against the shop. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:write')")
    ShopOrders.ShopOrderDetail cancel(CurrentPrincipal principal, @PathVariable UUID id, @Valid @RequestBody MerchantOrderRequests.Reason request) {
        return orders.move(principal, id, OrderStatus.CANCELLED, request.reason());
    }
}
