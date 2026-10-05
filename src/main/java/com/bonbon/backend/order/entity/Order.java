package com.bonbon.backend.order.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.order.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** One order. Everything in it is a snapshot of what was true when it was placed. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The human-friendly order number, assigned by the database. */
    @Generated(event = EventType.INSERT)
    @Column(insertable = false, updatable = false)
    private Long number;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "vendor_name", nullable = false, updatable = false, columnDefinition = "text")
    private String vendorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private OrderStatus status;

    @Column(nullable = false)
    private int version;

    @Column(name = "payment_method", nullable = false, columnDefinition = "text")
    private String paymentMethod;

    @Column(name = "payment_status", nullable = false, columnDefinition = "text")
    private String paymentStatus = "PENDING";

    @Column(name = "delivery_name", nullable = false, columnDefinition = "text")
    private String deliveryName;

    @Column(name = "delivery_phone", nullable = false, columnDefinition = "text")
    private String deliveryPhone;

    @Column(name = "delivery_address", nullable = false, columnDefinition = "text")
    private String deliveryAddress;

    @Column(name = "delivery_lat", nullable = false)
    private double deliveryLat;

    @Column(name = "delivery_lng", nullable = false)
    private double deliveryLng;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "items_total", nullable = false)
    private int itemsTotal;

    @Column(nullable = false)
    private int discount;

    @Column(name = "delivery_fee", nullable = false)
    private int deliveryFee;

    @Column(name = "grand_total", nullable = false)
    private int grandTotal;

    @Column(name = "commission_amount", nullable = false)
    private int commissionAmount;

    @Column(name = "incident_hold", nullable = false)
    private boolean incidentHold;

    @Column(name = "idempotency_key", nullable = false, updatable = false, columnDefinition = "text")
    private String idempotencyKey;

    @Column(name = "client_ip", columnDefinition = "text")
    private String clientIp;

    @Column(name = "client_agent", columnDefinition = "text")
    private String clientAgent;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "out_for_delivery_at")
    private Instant outForDeliveryAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(UUID customerId, UUID vendorId, String vendorName, OrderStatus status, String paymentMethod,
            String deliveryName, String deliveryPhone, String deliveryAddress, double deliveryLat, double deliveryLng,
            String note, String idempotencyKey, String clientIp, String clientAgent) {
        this.customerId = customerId;
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.deliveryName = deliveryName;
        this.deliveryPhone = deliveryPhone;
        this.deliveryAddress = deliveryAddress;
        this.deliveryLat = deliveryLat;
        this.deliveryLng = deliveryLng;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        this.clientIp = clientIp;
        this.clientAgent = clientAgent;
        this.placedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void addItem(OrderItem item) {
        item.attachTo(this, items.size() + 1);
        items.add(item);
    }

    public void setTotals(int itemsTotal, int discount, int deliveryFee, int grandTotal, int commissionAmount) {
        this.itemsTotal = itemsTotal;
        this.discount = discount;
        this.deliveryFee = deliveryFee;
        this.grandTotal = grandTotal;
        this.commissionAmount = commissionAmount;
    }

    public UUID getId() {
        return id;
    }

    public Long getNumber() {
        return number;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getVendorName() {
        return vendorName;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public String getPaymentMethod() {
        return paymentMethod;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getDeliveryName() {
        return deliveryName;
    }

    public String getDeliveryPhone() {
        return deliveryPhone;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public double getDeliveryLat() {
        return deliveryLat;
    }

    public double getDeliveryLng() {
        return deliveryLng;
    }

    public String getNote() {
        return note;
    }

    public int getItemsTotal() {
        return itemsTotal;
    }

    public int getDiscount() {
        return discount;
    }

    public int getDeliveryFee() {
        return deliveryFee;
    }

    public int getGrandTotal() {
        return grandTotal;
    }

    public int getCommissionAmount() {
        return commissionAmount;
    }

    public boolean isIncidentHold() {
        return incidentHold;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getOutForDeliveryAt() {
        return outForDeliveryAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
