package com.bonbon.backend.ordering.shopperformance.service;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchantapproval.ClosureTally;
import com.bonbon.backend.marketplace.merchantapproval.ClosureWork;
import com.bonbon.backend.ordering.shopperformance.ShopCaseStatus;
import org.springframework.stereotype.Component;

/** A closing shop must see its order cases and penalty appeals decided. */
@Component
class CaseClosureWork implements ClosureWork {

    private final ShopCaseStatus cases;

    CaseClosureWork(ShopCaseStatus cases) {
        this.cases = cases;
    }

    @Override
    public void count(UUID vendorId, ClosureTally tally) {
        tally.openCases(cases.openCases(vendorId));
        tally.pendingAppeals(cases.pendingAppeals(vendorId));
    }
}
