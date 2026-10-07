package com.bonbon.backend.order.service;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.repository.OrderRepository;
import com.bonbon.backend.payment.OnlinePayments;
import com.bonbon.backend.payment.PaymentConfirmed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to a confirmed MoMo payment inside the IPN transaction (a plain event listener, on purpose): an unpaid order
 * becomes a placed one at once, and money that arrives for an order that is already closed is queued for a refund.
 */
@Component
class OnlinePaymentListener {

    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentListener.class);

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final OnlinePayments payments;

    OnlinePaymentListener(OrderRepository orders, OrderTransitions transitions, OnlinePayments payments) {
        this.orders = orders;
        this.transitions = transitions;
        this.payments = payments;
    }

    @EventListener
    void onPaid(PaymentConfirmed event) {
        Order order = orders.findById(event.orderId()).orElse(null);
        if (order == null) {
            log.error("Payment {} confirmed for an unknown order {}", event.paymentId(), event.orderId());
            payments.requestLateRefund(event.paymentId());
            return;
        }
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            transitions.move(order, OrderStatus.PLACED, ActorType.SYSTEM, null, null);
        } else {
            // Cancelled by the payment timeout (or by the customer) a moment before the money came in: never revived.
            log.info("Payment {} arrived for order {} which is {}", event.paymentId(), order.getId(), order.getStatus());
            payments.requestLateRefund(event.paymentId());
        }
    }
}
