/**
 * The places bonbon serves, curated by administrators: each a circle (centre and radius) with the largest delivery radius a shop
 * inside it may set. Other modules ask which area contains a point through {@link com.bonbon.backend.marketplace.servicearea.ServiceAreas}.
 */
@NamedInterface("servicearea")
package com.bonbon.backend.marketplace.servicearea;

import org.springframework.modulith.NamedInterface;
