/**
 * Where an order stands: placing it at the prices of the moment, the shop confirming, preparing and delivering it, cancelling,
 * reviews, and what happens when an order goes wrong (cases, faults, penalties).
 * <p>
 * Its parts (order, orderfulfillment, shopperformance) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Ordering")
package com.bonbon.backend.ordering;

import org.springframework.modulith.ApplicationModule;
