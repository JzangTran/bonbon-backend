/**
 * Helping people and settling what they report (flows/support/). For now the administrators' lookup of orders and
 * customers with masked contact details, and the audit trail of every search, opening and reveal. Reads other modules
 * only through their lookup interfaces: {@link com.bonbon.backend.order.AdminOrders},
 * {@link com.bonbon.backend.payment.PaymentLookup}, {@link com.bonbon.backend.shopperformance.OrderCaseLookup},
 * {@link com.bonbon.backend.messaging.ConversationLookup} and {@link com.bonbon.backend.authentication.CustomerDirectory}.
 */
@ApplicationModule(displayName = "Support")
package com.bonbon.backend.support;

import org.springframework.modulith.ApplicationModule;
