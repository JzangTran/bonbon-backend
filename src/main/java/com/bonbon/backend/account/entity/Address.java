package com.bonbon.backend.account.entity;

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

/** A saved delivery address: a picked Goong place, plus the building/floor detail Goong cannot know. */
@Entity
@Table(name = "addresses")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, columnDefinition = "text")
    private String label;

    @Column(name = "goong_place_id", nullable = false, columnDefinition = "text")
    private String placeId;

    @Column(name = "formatted_address", nullable = false, columnDefinition = "text")
    private String formattedAddress;

    @Column(columnDefinition = "text")
    private String province;

    @Column(columnDefinition = "text")
    private String ward;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "recipient_name", nullable = false, columnDefinition = "text")
    private String recipientName;

    @Column(name = "recipient_phone", nullable = false, columnDefinition = "text")
    private String recipientPhone;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Address() {
    }

    public Address(UUID customerId) {
        this.customerId = customerId;
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

    public void setPlace(String placeId, String formattedAddress, String ward, String province, double lat, double lng) {
        this.placeId = placeId;
        this.formattedAddress = formattedAddress;
        this.ward = ward;
        this.province = province;
        this.lat = lat;
        this.lng = lng;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getPlaceId() {
        return placeId;
    }

    public String getFormattedAddress() {
        return formattedAddress;
    }

    public String getProvince() {
        return province;
    }

    public String getWard() {
        return ward;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public void setRecipientName(String recipientName) {
        this.recipientName = recipientName;
    }

    public String getRecipientPhone() {
        return recipientPhone;
    }

    public void setRecipientPhone(String recipientPhone) {
        this.recipientPhone = recipientPhone;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }

    public void setDefaultAddress(boolean defaultAddress) {
        this.defaultAddress = defaultAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
