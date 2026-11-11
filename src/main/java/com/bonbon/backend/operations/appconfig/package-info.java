/**
 * What the platform tells its apps about itself (reference/architecture/app-operations.md): the oldest app build still
 * supported, and maintenance mode. One filter in front of the API enforces both; {@link MaintenanceState} lets other modules ask
 * whether maintenance is on. Depends only on common.
 */
@NamedInterface("appconfig")
package com.bonbon.backend.operations.appconfig;

import org.springframework.modulith.NamedInterface;
