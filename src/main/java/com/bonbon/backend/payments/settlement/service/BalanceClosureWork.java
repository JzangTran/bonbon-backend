package com.bonbon.backend.payments.settlement.service;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchantapproval.ClosureTally;
import com.bonbon.backend.marketplace.merchantapproval.ClosureWork;
import com.bonbon.backend.payments.settlement.ShopBalances;
import org.springframework.stereotype.Component;

/** A closing shop must have nothing held for cases and a ledger at zero. */
@Component
class BalanceClosureWork implements ClosureWork {

    private final ShopBalances balances;

    BalanceClosureWork(ShopBalances balances) {
        this.balances = balances;
    }

    @Override
    public void count(UUID vendorId, ClosureTally tally) {
        tally.heldForCases(balances.heldForCases(vendorId));
        tally.balance(balances.balance(vendorId));
    }
}
