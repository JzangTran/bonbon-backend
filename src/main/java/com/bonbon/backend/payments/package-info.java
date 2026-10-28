/**
 * Money: cash on delivery and MoMo payments, refunds, commission rates, the append-only ledger of each shop, payouts and
 * statements.
 * <p>
 * Its parts (payment, settlement) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Payments & Settlement")
package com.bonbon.backend.payments.payments;

import org.springframework.modulith.ApplicationModule;
