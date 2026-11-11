package com.bonbon.backend.ordering.order.service;

import java.util.EnumSet;
import java.util.UUID;

import com.bonbon.backend.marketplace.merchantapproval.ClosureTally;
import com.bonbon.backend.marketplace.merchantapproval.ClosureWork;
import com.bonbon.backend.ordering.order.OrderStatus;
import com.bonbon.backend.ordering.order.ShopOrders;
import org.springframework.stereotype.Component;

/** A closing shop must finish the orders it is still making or delivering. */
@Component
class OrderClosureWork implements ClosureWork {

    private final ShopOrders orders;

    OrderClosureWork(ShopOrders orders) {
        this.orders = orders;
    }

    @Override
    public void count(UUID vendorId, ClosureTally tally) {
        tally.inProgressOrders(orders.list(vendorId, EnumSet.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY),
                null, null, false, 0, 1).total());
    }
}
