package com.bonbon.backend.servicearea.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "service_areas")
public class ServiceArea {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "center_lat", nullable = false)
    private double centerLat;

    @Column(name = "center_lng", nullable = false)
    private double centerLng;

    @Column(name = "radius_km", nullable = false, precision = 5, scale = 1)
    private BigDecimal radiusKm;

    @Column(name = "max_delivery_radius_km", nullable = false, precision = 4, scale = 1)
    private BigDecimal maxDeliveryRadiusKm;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by_id")
    private UUID createdById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ServiceArea() {
    }

    public ServiceArea(String name, double centerLat, double centerLng, BigDecimal radiusKm, BigDecimal maxDeliveryRadiusKm, UUID createdById) {
        this.name = name;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.radiusKm = radiusKm;
        this.maxDeliveryRadiusKm = maxDeliveryRadiusKm;
        this.createdById = createdById;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String name, double centerLat, double centerLng, BigDecimal radiusKm, BigDecimal maxDeliveryRadiusKm, boolean active) {
        this.name = name;
        this.centerLat = centerLat;
        this.centerLng = centerLng;
        this.radiusKm = radiusKm;
        this.maxDeliveryRadiusKm = maxDeliveryRadiusKm;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public double getCenterLng() {
        return centerLng;
    }

    public BigDecimal getRadiusKm() {
        return radiusKm;
    }

    public BigDecimal getMaxDeliveryRadiusKm() {
        return maxDeliveryRadiusKm;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
