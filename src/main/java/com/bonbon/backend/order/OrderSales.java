package com.bonbon.backend.order;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sales figures of one shop, read from its delivered orders. Gross: what customers paid, delivery fee included, with no
 * commission taken off (that is settlement's question). Only {@code DELIVERED} orders count, bucketed by the moment they
 * were delivered, in Vietnam time.
 */
public interface OrderSales {

    /** Orders and revenue per day, week (from Monday) or month with at least one delivered order, oldest first. */
    List<Bucket> revenue(UUID vendorId, Instant from, Instant to, String granularity);

    /** Dishes by quantity sold, from the order snapshots (a renamed or removed dish shows the name it had when ordered). */
    List<TopDish> topDishes(UUID vendorId, Instant from, Instant to, int limit);

    record Bucket(LocalDate start, long orders, long revenue) {
    }

    record TopDish(UUID menuItemId, String name, long quantity, long revenue) {
    }
}
