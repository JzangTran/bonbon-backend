package com.bonbon.backend.order.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.order.entity.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    List<OrderStatusHistory> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
