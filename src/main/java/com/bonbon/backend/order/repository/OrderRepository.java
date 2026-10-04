package com.bonbon.backend.order.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
