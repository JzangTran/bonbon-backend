/**
 * Payments: one row per order whatever the method, the MoMo payment attempts behind an online order, the signed
 * IPN, the reconciliation of missed IPNs and the refund requests (flows/payment/). The order module reaches this
 * module through {@link com.bonbon.backend.ordering.order.OnlinePayments}; this module never calls the order module back,
 * it publishes {@link com.bonbon.backend.ordering.order.PaymentConfirmed} for the order to react to.
 */
@NamedInterface("payment")
package com.bonbon.backend.payments.payment;

import com.bonbon.backend.ordering.order.PaymentConfirmed;

import com.bonbon.backend.ordering.order.OnlinePayments;

import org.springframework.modulith.NamedInterface;
