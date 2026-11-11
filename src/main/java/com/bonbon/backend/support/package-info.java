/**
 * Helping people and settling what they report (flows/support/). For now the administrators' lookup of orders and
 * customers with masked contact details, and the audit trail of every search, opening and reveal. Reads other modules
 * only through their lookup interfaces: {@link com.bonbon.backend.ordering.order.AdminOrders},
 * {@link com.bonbon.backend.payments.payment.PaymentLookup}, {@link com.bonbon.backend.ordering.shopperformance.OrderCaseLookup},
 * {@link com.bonbon.backend.support.ConversationLookup} and {@link com.bonbon.backend.identity.authentication.CustomerDirectory}.
 */
@ApplicationModule(displayName = "Support")
package com.bonbon.backend.support;

import org.springframework.modulith.ApplicationModule;
