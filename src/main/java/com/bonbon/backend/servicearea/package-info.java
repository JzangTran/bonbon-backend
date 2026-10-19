/**
 * The places bonbon serves, curated by administrators: each a circle (centre and radius) with the largest delivery radius a shop
 * inside it may set. Other modules ask which area contains a point through {@link com.bonbon.backend.servicearea.ServiceAreas}.
 */
@ApplicationModule(displayName = "Service area")
package com.bonbon.backend.servicearea;

import org.springframework.modulith.ApplicationModule;
