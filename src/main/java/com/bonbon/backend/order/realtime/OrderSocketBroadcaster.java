package com.bonbon.backend.order.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.bonbon.backend.messaging.MessageSent;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

/**
 * Tells the customer and the shop that an order changed, strictly after the change is committed: a message about a
 * rolled-back change would be a lie, and the client re-fetches anyway.
 */
@Component
class OrderSocketBroadcaster {

    private final OrderSocketRegistry registry;

    OrderSocketBroadcaster(OrderSocketRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChanged(OrderStatusChanged event) {
        push(OrderSocketHandler.customerChannel(event.customerId()), "customer", event);
        // An order nobody has paid for is not the shop's yet (it first hears of it when it becomes PLACED).
        boolean unpaidOnly = event.to() == OrderStatus.PENDING_PAYMENT
                || (event.from() == OrderStatus.PENDING_PAYMENT && event.to() != OrderStatus.PLACED);
        if (!unpaidOnly) {
            push(OrderSocketHandler.vendorChannel(event.vendorId()), "shop", event);
        }
    }

    /** A chat message reaches the other side's open sockets; the sender already has it from the request. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMessage(MessageSent event) {
        boolean toShop = "CUSTOMER".equals(event.sender());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "message");
        message.put("channel", toShop ? "shop" : "customer");
        message.put("conversationId", event.conversationId());
        message.put("messageId", event.messageId());
        deliver(toShop ? OrderSocketHandler.vendorChannel(event.vendorId()) : OrderSocketHandler.customerChannel(event.customerId()), message);
    }

    private void push(String channel, String audience, OrderStatusChanged event) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "order");
        message.put("channel", audience);
        message.put("orderId", event.orderId());
        message.put("number", event.number());
        message.put("from", event.from());
        message.put("to", event.to());
        deliver(channel, message);
    }

    private void deliver(String channel, Map<String, Object> message) {
        Instant now = Instant.now();
        for (OrderSocketRegistry.Subscription s : registry.listeners(channel)) {
            if (now.isAfter(s.validUntil())) {
                OrderSocketHandler.close(s.session(), CloseStatus.POLICY_VIOLATION);
                registry.remove(s.session());
            } else {
                OrderSocketHandler.send(s.session(), message);
            }
        }
    }
}
