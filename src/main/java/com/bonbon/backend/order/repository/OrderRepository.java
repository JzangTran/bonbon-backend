package com.bonbon.backend.order.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    long countByCustomerIdAndStatusIn(UUID customerId, Collection<OrderStatus> statuses);

    @Query("select o from Order o where o.customerId = :customerId order by o.placedAt desc")
    Page<Order> findForCustomer(@Param("customerId") UUID customerId, Pageable pageable);

    @Query("select o from Order o where o.customerId = :customerId and o.status in :statuses order by o.placedAt desc")
    Page<Order> findForCustomerWithStatus(@Param("customerId") UUID customerId,
            @Param("statuses") Collection<OrderStatus> statuses, Pageable pageable);

    /** One row per line, in order, for the list previews (avoids loading every item graph for a page of orders). */
    @Query("select i.order.id, i.name, i.quantity from OrderItem i where i.order.id in :orderIds order by i.order.id, i.position")
    List<Object[]> linePreviews(@Param("orderIds") Collection<UUID> orderIds);

    Optional<Order> findByIdAndVendorId(UUID id, UUID vendorId);

    /**
     * The one conditional update behind every status change: it only happens when the order is still in
     * {@code from}. Zero rows means somebody else moved it first. Timestamps and payment status are only set
     * when given.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.status = :to, o.version = o.version + 1, o.updatedAt = :now,
                   o.confirmedAt = coalesce(:confirmedAt, o.confirmedAt),
                   o.outForDeliveryAt = coalesce(:outForDeliveryAt, o.outForDeliveryAt),
                   o.finishedAt = coalesce(:finishedAt, o.finishedAt),
                   o.placedAt = coalesce(:placedAt, o.placedAt),
                   o.paymentStatus = coalesce(:paymentStatus, o.paymentStatus)
            where o.id = :id and o.status = :from""")
    int transition(@Param("id") UUID id, @Param("from") OrderStatus from, @Param("to") OrderStatus to, @Param("now") Instant now,
            @Param("confirmedAt") Instant confirmedAt, @Param("outForDeliveryAt") Instant outForDeliveryAt,
            @Param("finishedAt") Instant finishedAt, @Param("placedAt") Instant placedAt, @Param("paymentStatus") String paymentStatus);

    @Query("select o from Order o where o.vendorId = :vendorId and o.status in :statuses and o.placedAt >= :from and o.placedAt < :to")
    Page<Order> findForVendor(@Param("vendorId") UUID vendorId, @Param("statuses") Collection<OrderStatus> statuses,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    /** New orders the shop has left unanswered since before {@code cutoff}, oldest first. */
    @Query("select o.id from Order o where o.status = 'PLACED' and o.placedAt < :cutoff order by o.placedAt")
    List<UUID> unansweredSince(@Param("cutoff") Instant cutoff, Pageable limit);

    /** Confirmed orders that have not left the kitchen since before {@code cutoff}. */
    @Query("select o.id from Order o where o.status in ('CONFIRMED', 'PREPARING') and o.confirmedAt < :cutoff order by o.confirmedAt")
    List<UUID> notHandedOverSince(@Param("cutoff") Instant cutoff, Pageable limit);

    /** Orders out for delivery since before {@code cutoff} that nobody has closed and no case is holding. */
    @Query("select o.id from Order o where o.status = 'OUT_FOR_DELIVERY' and o.incidentHold = false and o.outForDeliveryAt < :cutoff order by o.outForDeliveryAt")
    List<UUID> undeliveredSince(@Param("cutoff") Instant cutoff, Pageable limit);

    /** Online orders still waiting for payment since before {@code cutoff}, oldest first. */
    @Query("select o.id from Order o where o.status = 'PENDING_PAYMENT' and o.placedAt < :cutoff order by o.placedAt")
    List<UUID> unpaidSince(@Param("cutoff") Instant cutoff, Pageable limit);
}
