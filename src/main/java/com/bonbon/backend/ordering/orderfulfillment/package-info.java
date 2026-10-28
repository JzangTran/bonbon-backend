/**
 * The seller's side of orders: the list of new and past orders, an order's detail, and moving it along
 * (confirm, reject, preparing, out for delivery, delivered, cancel). The orders themselves belong to
 * {@link com.bonbon.backend.ordering.order.ShopOrders}'s module.
 */
@NamedInterface("orderfulfillment")
package com.bonbon.backend.ordering.orderfulfillment;

import org.springframework.modulith.NamedInterface;
