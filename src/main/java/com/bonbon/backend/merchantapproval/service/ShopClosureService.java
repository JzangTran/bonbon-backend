package com.bonbon.backend.merchantapproval.service;

import java.util.EnumSet;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchant.ShopClosureChanged;
import com.bonbon.backend.merchantapproval.dto.ClosureDtos;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import com.bonbon.backend.settlement.ShopBalances;
import com.bonbon.backend.shopperformance.ShopCaseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finishing the closing of a shop (flows/merchant/close-shop.md): a shop that asked to close becomes CLOSED once nothing is left
 * to settle. Orders still going, cases and appeals still undecided, money held for a case, or a ledger that is not at zero all stand
 * in the way; an administrator clears the money by recording the payout, or by writing a debt off with an adjustment and a reason.
 * A job closes the shops that are ready; an administrator can do it by hand.
 */
@Service
public class ShopClosureService {

    private static final Logger log = LoggerFactory.getLogger(ShopClosureService.class);

    private final ShopApplications shops;
    private final ShopOrders orders;
    private final ShopCaseStatus cases;
    private final ShopBalances balances;
    private final ApplicationEventPublisher events;

    ShopClosureService(ShopApplications shops, ShopOrders orders, ShopCaseStatus cases, ShopBalances balances, ApplicationEventPublisher events) {
        this.shops = shops;
        this.orders = orders;
        this.cases = cases;
        this.balances = balances;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public ClosureDtos.Rows closing() {
        return new ClosureDtos.Rows(shops.closing().stream()
                .map(c -> new ClosureDtos.Row(c.vendorId(), c.name(), c.reason(), c.reasonText(), c.requestedAt(), blockers(c.vendorId()))).toList());
    }

    @Transactional(readOnly = true)
    public ClosureDtos.Blockers blockers(UUID vendorId) {
        long inProgress = orders.list(vendorId, EnumSet.of(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY), null, null, false, 0, 1).total();
        long openCases = cases.openCases(vendorId);
        long appeals = cases.pendingAppeals(vendorId);
        long held = balances.heldForCases(vendorId);
        long balance = balances.balance(vendorId);
        return new ClosureDtos.Blockers(inProgress, openCases, appeals, held, balance, inProgress == 0 && openCases == 0 && appeals == 0 && held == 0 && balance == 0);
    }

    /** An administrator closes a shop that is ready, now. */
    @Transactional
    public ClosureDtos.Blockers finish(UUID vendorId) {
        ClosureDtos.Blockers blockers = blockers(vendorId);
        if (!blockers.ready()) {
            throw new BusinessException(HttpStatus.CONFLICT, "CLOSURE_BLOCKED", "Cửa hàng còn việc chưa xong nên chưa đóng được.")
                    .withProperty("blockers", blockers);
        }
        ShopApplications.Decided closed = shops.finishClosing(vendorId);
        events.publishEvent(new ShopClosureChanged(closed.vendorId(), closed.ownerUserId(), closed.shopName(), "CLOSED", null));
        return blockers;
    }

    /** Closes every shop that is ready; returns how many. A shop that fails is skipped and tried again next time. */
    public int finishReady() {
        int closed = 0;
        for (ShopApplications.Closing c : shops.closing()) {
            try {
                if (blockers(c.vendorId()).ready()) {
                    finish(c.vendorId());
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not close shop {}", c.vendorId(), e);
            }
        }
        return closed;
    }

}
