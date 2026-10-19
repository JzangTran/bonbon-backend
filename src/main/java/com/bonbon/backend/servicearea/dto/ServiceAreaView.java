package com.bonbon.backend.servicearea.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ServiceArea")
public record ServiceAreaView(UUID id, String name, double centerLat, double centerLng, BigDecimal radiusKm,
        BigDecimal maxDeliveryRadiusKm, boolean active, Instant updatedAt) {
}
