package com.bonbon.backend.servicearea;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask of the service areas. */
public interface ServiceAreas {

    /**
     * The active area that contains the point; when several do, the one whose centre is nearest. Empty when the point is outside
     * every area.
     */
    Optional<Area> locate(double lat, double lng);

    /** An area by id, active or not (a shop keeps the area it was placed in). */
    Optional<Area> find(UUID id);

    record Area(UUID id, String name, BigDecimal maxDeliveryRadiusKm, boolean active) {
    }
}
