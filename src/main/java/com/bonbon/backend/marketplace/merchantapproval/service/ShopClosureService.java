package com.bonbon.backend.marketplace.merchantapproval.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.ShopApplications;
import com.bonbon.backend.marketplace.merchant.ShopClosureChanged;
import com.bonbon.backend.marketplace.merchantapproval.ClosureTally;
import com.bonbon.backend.marketplace.merchantapproval.ClosureWork;
import com.bonbon.backend.marketplace.merchantapproval.dto.ClosureDtos;
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
    private final List<ClosureWork> work;
    private final ApplicationEventPublisher events;

    ShopClosureService(ShopApplications shops, List<ClosureWork> work, ApplicationEventPublisher events) {
        this.shops = shops;
        this.work = work;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public ClosureDtos.Rows closing() {
        return new ClosureDtos.Rows(shops.closing().stream()
                .map(c -> new ClosureDtos.Row(c.vendorId(), c.name(), c.reason(), c.reasonText(), c.requestedAt(), blockers(c.vendorId()))).toList());
    }

    @Transactional(readOnly = true)
    public ClosureDtos.Blockers blockers(UUID vendorId) {
        ClosureTally tally = new ClosureTally();
        work.forEach((w) -> w.count(vendorId, tally));
        long inProgress = tally.inProgressOrders();
        long openCases = tally.openCases();
        long appeals = tally.pendingAppeals();
        long held = tally.heldForCases();
        long balance = tally.balance();
        return new ClosureDtos.Blockers(inProgress, openCases, appeals, held, balance, inProgress == 0 && openCases == 0 && appeals == 0 && held == 0 && balance == 0);
    }

    /** The caller's own shop while it is closing: the same blockers the administrators see, so the owner knows what to finish. */
    @Transactional(readOnly = true)
    public ClosureDtos.Mine mine(CurrentPrincipal caller) {
        ShopApplications.Closing c = shops.closing().stream().filter(x -> caller.id().equals(x.ownerUserId())).findFirst()
                .orElseThrow(() -> BusinessException.conflict("SHOP_NOT_CLOSING", "Cửa hàng của bạn không đang trong quá trình đóng."));
        return new ClosureDtos.Mine(c.name(), c.reason(), c.reasonText(), c.requestedAt(), blockers(c.vendorId()));
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
