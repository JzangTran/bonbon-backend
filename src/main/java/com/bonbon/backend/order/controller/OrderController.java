package com.bonbon.backend.order.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.dto.OrderRequests;
import com.bonbon.backend.order.dto.OrderViews;
import com.bonbon.backend.order.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own orders; another customer's order id is simply not found. */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasAuthority('order:create')")
@Validated
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    /**
     * Places a cash-on-delivery order. The same {@code Idempotency-Key} always returns the same order (200 on a
     * repeat, 201 the first time), so a retry after a timeout or a double tap never creates a second one.
     */
    @PostMapping
    ResponseEntity<OrderViews.Detail> place(CurrentPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody OrderRequests.Place request, HttpServletRequest http) {
        OrderService.Placed placed = orders.place(principal, idempotencyKey, request, ClientContext.from(http),
                http.getHeader("User-Agent"));
        return ResponseEntity.status(placed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(placed.order());
    }

    /** Newest first. {@code status} may be repeated or comma separated; empty means every status. */
    @GetMapping
    OrderViews.Page list(CurrentPrincipal principal, @RequestParam(required = false) List<OrderStatus> status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.list(principal.id(), status, page, size);
    }

    @GetMapping("/{id}")
    OrderViews.Detail get(CurrentPrincipal principal, @PathVariable UUID id) {
        return orders.get(principal.id(), id);
    }
}
