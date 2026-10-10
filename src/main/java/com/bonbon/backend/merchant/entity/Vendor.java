package com.bonbon.backend.merchant.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.merchant.ShopCommissionStanding;
import com.bonbon.backend.merchant.VendorStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A shop. Fields stay nullable while it is a draft; completeness is checked at submission. */
@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private VendorStatus status = VendorStatus.DRAFT;

    @Column(columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String phone;

    @Column(columnDefinition = "text")
    private String email;

    @Column(name = "goong_place_id", columnDefinition = "text")
    private String placeId;

    @Column(name = "formatted_address", columnDefinition = "text")
    private String formattedAddress;

    @Column(columnDefinition = "text")
    private String province;

    @Column(columnDefinition = "text")
    private String ward;

    @Column(name = "address_detail", columnDefinition = "text")
    private String addressDetail;

    private Double lat;

    private Double lng;

    @Column(name = "delivery_radius_km", precision = 4, scale = 1)
    private BigDecimal deliveryRadiusKm;

    @Column(name = "delivery_fee")
    private Integer deliveryFee;

    @Column(name = "free_delivery_threshold")
    private Integer freeDeliveryThreshold;

    @Column(name = "min_order_value")
    private Integer minOrderValue;

    /** Kept in step with the reviews by {@code ShopRatings}; never written through the entity. */
    @Column(name = "rating_sum", nullable = false, insertable = false, updatable = false)
    private int ratingSum;

    @Column(name = "rating_count", nullable = false, insertable = false, updatable = false)
    private int ratingCount;

    /** Set by settlement through {@code ShopCommissionStanding}; never written through the entity. */
    @Column(name = "commission_stage", nullable = false, insertable = false, updatable = false, columnDefinition = "text")
    private String commissionStage = "NONE";

    /** Set by shop performance through {@code ShopPerformanceStanding}; never written through the entity. */
    @Column(name = "performance_restricted", nullable = false, insertable = false, updatable = false)
    private boolean performanceRestricted;

    @Column(name = "accepting_orders", nullable = false)
    private boolean acceptingOrders = true;

    @Column(name = "accepting_orders_changed_at")
    private Instant acceptingOrdersChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "accepting_orders_changed_by_type", columnDefinition = "text")
    private ActorType acceptingOrdersChangedByType;

    @Column(name = "accepting_orders_changed_by_id")
    private UUID acceptingOrdersChangedById;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shop_opening_hours", joinColumns = @JoinColumn(name = "vendor_id"))
    private List<OpeningWindow> openingHours = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Vendor() {
    }

    public Vendor(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
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

    public void setAddress(String placeId, String formattedAddress, String ward, String province, Double lat, Double lng) {
        this.placeId = placeId;
        this.formattedAddress = formattedAddress;
        this.ward = ward;
        this.province = province;
        this.lat = lat;
        this.lng = lng;
    }

    public void setShipping(BigDecimal radiusKm, Integer fee, Integer freeThreshold, Integer minOrder) {
        this.deliveryRadiusKm = radiusKm;
        this.deliveryFee = fee;
        this.freeDeliveryThreshold = freeThreshold;
        this.minOrderValue = minOrder;
    }

    public void replaceOpeningHours(List<OpeningWindow> windows) {
        openingHours.clear();
        openingHours.addAll(windows);
    }

    public void submit(Instant at) {
        this.status = VendorStatus.PENDING;
        this.submittedAt = at;
    }

    /** An approved shop changed a field the administrator checked (its address): it goes back to review. */
    public void returnToReview(Instant at) {
        this.status = VendorStatus.PENDING;
        this.submittedAt = at;
    }

    public void approve(Instant at) {
        this.status = VendorStatus.APPROVED;
        this.decidedAt = at;
        this.rejectionReason = null;
    }

    public void reject(String reason, Instant at) {
        this.status = VendorStatus.REJECTED;
        this.rejectionReason = reason;
        this.decidedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public VendorStatus getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    public String getAddressDetail() {
        return addressDetail;
    }

    public void setAddressDetail(String addressDetail) {
        this.addressDetail = addressDetail;
    }

    public Double getLat() {
        return lat;
    }

    public Double getLng() {
        return lng;
    }

    public BigDecimal getDeliveryRadiusKm() {
        return deliveryRadiusKm;
    }

    public Integer getDeliveryFee() {
        return deliveryFee;
    }

    public Integer getFreeDeliveryThreshold() {
        return freeDeliveryThreshold;
    }

    public Integer getMinOrderValue() {
        return minOrderValue;
    }

    public int getRatingSum() {
        return ratingSum;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public boolean isPerformanceRestricted() {
        return performanceRestricted;
    }

    public ShopCommissionStanding.Stage getCommissionStage() {
        return ShopCommissionStanding.Stage.valueOf(commissionStage);
    }

    public boolean isAcceptingOrders() {
        return acceptingOrders;
    }

    public void setAcceptingOrders(boolean accepting, ActorType byType, UUID byId, Instant at) {
        this.acceptingOrders = accepting;
        this.acceptingOrdersChangedAt = at;
        this.acceptingOrdersChangedByType = byType;
        this.acceptingOrdersChangedById = byId;
    }

    public Instant getAcceptingOrdersChangedAt() {
        return acceptingOrdersChangedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public List<OpeningWindow> getOpeningHours() {
        return List.copyOf(openingHours);
    }
}
