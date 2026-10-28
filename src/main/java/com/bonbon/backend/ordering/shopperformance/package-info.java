/**
 * Complaints about delivered orders and how well shops do (flows/shop-performance/): order cases that a customer files
 * with evidence, the shop's answer, and the administrator's decision when they disagree. The case owns its own tables;
 * money effects go through {@link com.bonbon.backend.ordering.shopperformance.CaseHolds} and the ledger, and the order's data comes
 * from {@link com.bonbon.backend.ordering.order.OrderIncidents}. Other modules only hear about cases through events.
 */
@NamedInterface("shopperformance")
package com.bonbon.backend.ordering.shopperformance;

import org.springframework.modulith.NamedInterface;
