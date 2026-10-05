package com.bonbon.backend.order.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The order timers (flows/order/README.md): nothing may hang forever. Each rule finds the orders past its limit and
 * moves every one in its own transaction through the same conditional update as any other change, so an order the
 * shop confirmed a moment ago is simply skipped. The limits are system settings ({@link OrderSettings}).
 */
@Component
public class OrderTimers {

    private static final Logger log = LoggerFactory.getLogger(OrderTimers.class);
    /** A backlog is worked off in slices; the next run takes the rest. */
    private static final PageRequest BATCH = PageRequest.of(0, 200);

    static final String NO_RESPONSE = "Quán không phản hồi kịp thời.";
    static final String NOT_HANDED_OVER = "Quán chưa giao món trong thời hạn cho phép.";

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final OrderSettings settings;
    private final TransactionTemplate tx;

    OrderTimers(OrderRepository orders, OrderTransitions transitions, OrderSettings settings, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.transitions = transitions;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** How many orders each rule moved in one run. */
    public record Result(int rejected, int cancelled, int delivered) {
    }

    /** Runs all three rules as if it were {@code now}. */
    public Result runOnce(Instant now) {
        int rejected = apply(orders.unansweredSince(now.minus(settings.sellerResponse()), BATCH), OrderStatus.PLACED, OrderStatus.REJECTED, NO_RESPONSE);
        int cancelled = apply(orders.notHandedOverSince(now.minus(settings.handover()), BATCH), null, OrderStatus.CANCELLED, NOT_HANDED_OVER);
        int delivered = apply(orders.undeliveredSince(now.minus(settings.autoDelivered()), BATCH), OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, null);
        if (rejected + cancelled + delivered > 0) {
            log.info("Order timers: {} rejected, {} cancelled, {} delivered", rejected, cancelled, delivered);
        }
        return new Result(rejected, cancelled, delivered);
    }

    private int apply(List<UUID> ids, OrderStatus expected, OrderStatus to, String reason) {
        int moved = 0;
        for (UUID id : ids) {
            try {
                Boolean done = tx.execute(status -> {
                    Order order = orders.findById(id).orElse(null);
                    // Someone may have moved it since the list was read; then there is nothing left to do.
                    if (order == null || order.getStatus().isTerminal() || (expected != null && order.getStatus() != expected)) {
                        return false;
                    }
                    transitions.move(order, to, ActorType.SYSTEM, null, reason);
                    return true;
                });
                if (Boolean.TRUE.equals(done)) {
                    moved++;
                }
            } catch (BusinessException e) {
                log.debug("Order {} changed under the timer: {}", id, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Order timer failed for order {}", id, e);
            }
        }
        return moved;
    }
}
