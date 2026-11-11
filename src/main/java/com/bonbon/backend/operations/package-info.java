/**
 * Running the platform: the oldest app version still served, maintenance mode, and the health of the system.
 * <p>
 * Its parts (appconfig, ops) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Operations")
package com.bonbon.backend.operations;

import org.springframework.modulith.ApplicationModule;
