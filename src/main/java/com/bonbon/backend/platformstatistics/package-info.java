/**
 * What the administrators see about the whole platform: orders, revenue and commission over time, revenue by shop, and
 * new accounts by role (flows/platform-statistics/). Like the shop's own statistics these are gross sales, not money owed.
 * This module is one of the two that may read other modules' tables, with read-only SQL, because aggregates across
 * orders, shops and users through facades would be slower and no safer; it never writes.
 */
@ApplicationModule(displayName = "Platform statistics")
package com.bonbon.backend.platformstatistics;

import org.springframework.modulith.ApplicationModule;
