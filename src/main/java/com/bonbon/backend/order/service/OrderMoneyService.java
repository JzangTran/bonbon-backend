package com.bonbon.backend.order.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.order.OrderMoney;
import com.bonbon.backend.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderMoneyService implements OrderMoney {

    private final OrderRepository orders;

    OrderMoneyService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Money> of(UUID orderId) {
        return orders.findById(orderId).map(o -> new Money(o.getId(), o.getVendorId(), o.getPaymentMethod(), o.getItemsTotal(), o.getDiscount(),
                o.getDeliveryFee(), o.getGrandTotal(), o.getCommissionAmount()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> numbers(Collection<UUID> orderIds) {
        Map<UUID, Long> result = new HashMap<>();
        if (!orderIds.isEmpty()) {
            orders.findAllById(orderIds).forEach(o -> result.put(o.getId(), o.getNumber()));
        }
        return result;
    }
}
