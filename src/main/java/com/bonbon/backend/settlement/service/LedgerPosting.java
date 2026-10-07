package com.bonbon.backend.settlement.service;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderMoney;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.OrderStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Posts an order to its shop's ledger when it ends (flows/settlement/README.md "How money actually moves"), in the
 * same transaction as the status change (a plain event listener, on purpose): if the posting fails the status change
 * is rolled back, so an order can never be delivered without its entry.
 *
 * <ul>
 *   <li>Online-paid, {@code DELIVERED} or {@code NOT_DELIVERED}: the platform holds the money, so it owes the shop
 *       {@code grand_total - commission} ({@code ONLINE_EARNING}).</li>
 *   <li>Cash, {@code DELIVERED}: the shop holds the money, so it owes the platform the commission
 *       ({@code COD_COMMISSION}). A cash order that ends {@code NOT_DELIVERED} posts nothing: no cash moved.</li>
 *   <li>Never at payment time, and never for cancelled or rejected orders: nothing was earned.</li>
 * </ul>
 */
@Component
class LedgerPosting {

    private static final Logger log = LoggerFactory.getLogger(LedgerPosting.class);

    private final OrderMoney orders;
    private final LedgerService ledger;

    LedgerPosting(OrderMoney orders, LedgerService ledger) {
        this.orders = orders;
        this.ledger = ledger;
    }

    @EventListener
    void onOrderChanged(OrderStatusChanged event) {
        if (event.to() != OrderStatus.DELIVERED && event.to() != OrderStatus.NOT_DELIVERED) {
            return;
        }
        OrderMoney.Money money = orders.of(event.orderId()).orElse(null);
        if (money == null) {
            log.error("Order {} ended but its money could not be read; nothing posted", event.orderId());
            throw new IllegalStateException("Order " + event.orderId() + " not found for the ledger");
        }
        if ("ONLINE".equals(money.paymentMethod())) {
            int earning = money.grandTotal() - money.commission();
            if (earning > 0) {
                ledger.postForOrder(money.vendorId(), "ONLINE_EARNING", earning, money.orderId(), ActorType.SYSTEM, null);
            }
        } else if (event.to() == OrderStatus.DELIVERED && money.commission() > 0) {
            ledger.postForOrder(money.vendorId(), "COD_COMMISSION", -money.commission(), money.orderId(), ActorType.SYSTEM, null);
        }
    }
}
