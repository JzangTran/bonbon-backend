/**
 * Read-only figures: a shop's revenue and best sellers, and the platform's overview, revenue by shop and user growth. Reads
 * what the other modules own and never writes.
 * <p>
 * Its parts (statistics, platformstatistics) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Insights")
package com.bonbon.backend.insights;

import org.springframework.modulith.ApplicationModule;
