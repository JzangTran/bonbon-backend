/**
 * Payments: one row per order whatever the method, the MoMo payment attempts behind an online order, the signed
 * IPN, the reconciliation of missed IPNs and the refund requests (flows/payment/). The order module reaches this
 * module through {@link com.bonbon.backend.payment.OnlinePayments}; this module never calls the order module back,
 * it publishes {@link com.bonbon.backend.payment.PaymentConfirmed} for the order to react to.
 */
@ApplicationModule(displayName = "Payment")
package com.bonbon.backend.payment;

import org.springframework.modulith.ApplicationModule;
