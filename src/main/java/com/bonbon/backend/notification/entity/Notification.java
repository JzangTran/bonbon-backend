package com.bonbon.backend.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A stored notification. {@code audience} is CUSTOMER or SHOP: an identity with both roles sees only the active one. */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String audience;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String type;

    @Column(name = "order_id", updatable = false)
    private UUID orderId;

    @Column(name = "order_number", updatable = false)
    private Long orderNumber;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    protected Notification() {
    }

    public Notification(UUID recipientId, String audience, String type, UUID orderId, Long orderNumber, String title, String body) {
        this.recipientId = recipientId;
        this.audience = audience;
        this.type = type;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.title = title;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getAudience() {
        return audience;
    }

    public String getType() {
        return type;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Long getOrderNumber() {
        return orderNumber;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }
}
