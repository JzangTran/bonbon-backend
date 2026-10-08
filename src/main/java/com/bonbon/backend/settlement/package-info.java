/**
 * Money between the platform and its shops (flows/settlement/): commission rates and their history, the per-shop
 * ledger, payouts and collections, earnings for the shop and the overview for the administrator. It listens to the
 * order and category modules and publishes nothing the others depend on.
 */
@ApplicationModule(displayName = "Settlement")
package com.bonbon.backend.settlement;

import org.springframework.modulith.ApplicationModule;
