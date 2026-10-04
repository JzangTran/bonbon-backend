package com.bonbon.backend.order.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One step of an order's timeline: who moved it from one status to the next, and why. */
@Entity
@Table(name = "order_status_history")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", columnDefinition = "text")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, columnDefinition = "text")
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "acted_by_type", nullable = false, columnDefinition = "text")
    private ActorType actedByType;

    @Column(name = "acted_by_id")
    private UUID actedById;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderStatusHistory() {
    }

    public OrderStatusHistory(UUID orderId, OrderStatus fromStatus, OrderStatus toStatus, ActorType actedByType,
            UUID actedById, String reason) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actedByType = actedByType;
        this.actedById = actedById;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public ActorType getActedByType() {
        return actedByType;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
