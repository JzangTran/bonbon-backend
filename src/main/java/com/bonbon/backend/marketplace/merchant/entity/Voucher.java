package com.bonbon.backend.marketplace.merchant.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A shop's discount code (manage-vouchers.md). Disabled, never deleted: past orders refer to it. */
@Entity
@Table(schema = "marketplace", name = "vouchers")
public class Voucher {

    public static final String PERCENT = "PERCENT";
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    @Id
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String code;

    @Column(name = "discount_type", nullable = false, columnDefinition = "text")
    private String discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    @Column(name = "max_discount")
    private Integer maxDiscount;

    @Column(name = "min_order_value")
    private Integer minOrderValue;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    /** Changed only by the atomic statements of the repository, never through this entity. */
    @Column(name = "used_count", nullable = false, insertable = false, updatable = false)
    private int usedCount;

    @Column(name = "max_per_customer")
    private Integer maxPerCustomer;

    @Column(nullable = false, columnDefinition = "text")
    private String status = ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Voucher() {
    }

    public Voucher(UUID vendorId, String code) {
        this.id = UUID.randomUUID();
        this.vendorId = vendorId;
        this.code = code;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Whole dong off this food value, rounded down and never more than the food itself. */
    public int discountOn(int itemsTotal) {
        long off = PERCENT.equals(discountType) ? (long) itemsTotal * discountValue / 100 : discountValue;
        if (maxDiscount != null) {
            off = Math.min(off, maxDiscount);
        }
        return (int) Math.min(off, itemsTotal);
    }

    public boolean inWindow(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validTo);
    }

    public boolean usedUp() {
        return usageLimit != null && usedCount >= usageLimit;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getCode() {
        return code;
    }

    public String getDiscountType() {
        return discountType;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public int getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(int discountValue) {
        this.discountValue = discountValue;
    }

    public Integer getMaxDiscount() {
        return maxDiscount;
    }

    public void setMaxDiscount(Integer maxDiscount) {
        this.maxDiscount = maxDiscount;
    }

    public Integer getMinOrderValue() {
        return minOrderValue;
    }

    public void setMinOrderValue(Integer minOrderValue) {
        this.minOrderValue = minOrderValue;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public void setWindow(Instant from, Instant to) {
        this.validFrom = from;
        this.validTo = to;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(Integer usageLimit) {
        this.usageLimit = usageLimit;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public Integer getMaxPerCustomer() {
        return maxPerCustomer;
    }

    public void setMaxPerCustomer(Integer maxPerCustomer) {
        this.maxPerCustomer = maxPerCustomer;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
