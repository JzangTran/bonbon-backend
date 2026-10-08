/**
 * Complaints about delivered orders and how well shops do (flows/shop-performance/): order cases that a customer files
 * with evidence, the shop's answer, and the administrator's decision when they disagree. The case owns its own tables;
 * money effects go through {@link com.bonbon.backend.settlement.CaseHolds} and the ledger, and the order's data comes
 * from {@link com.bonbon.backend.order.OrderIncidents}. Other modules only hear about cases through events.
 */
@ApplicationModule(displayName = "Shop performance")
package com.bonbon.backend.shopperformance;

import org.springframework.modulith.ApplicationModule;
