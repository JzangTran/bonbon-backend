package com.bonbon.backend.order.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.persistence.ActorType;
import com.bonbon.backend.order.OrderIncidents;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.entity.Order;
import com.bonbon.backend.order.repository.OrderRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderIncidentsService implements OrderIncidents {

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final OrderTransitions transitions;

    OrderIncidentsService(JdbcClient jdbc, OrderRepository orders, OrderTransitions transitions) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.transitions = transitions;
    }

    @Override
    @Transactional
    public boolean holdIfOutForDelivery(UUID orderId) {
        return jdbc.sql("update orders set incident_hold = true where id = :o and status = 'OUT_FOR_DELIVERY' and incident_hold = false")
                .param("o", orderId).update() > 0;
    }

    @Override
    @Transactional
    public void endNoShow(UUID orderId, String to, ActorType by, UUID actorId, String reason) {
        jdbc.sql("update orders set incident_hold = false where id = :o").param("o", orderId).update();
        Order order = orders.findById(orderId).orElseThrow(() -> BusinessException.notFound("ORDER_NOT_FOUND", "Không tìm thấy đơn hàng."));
        transitions.move(order, OrderStatus.valueOf(to), by, actorId, reason);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IncidentOrder> find(UUID orderId) {
        List<IncidentLine> lines = jdbc.sql("select id, name, quantity, line_total, allocated_discount, commission_amount from order_items where order_id = :o order by position")
                .param("o", orderId).query((rs, n) -> new IncidentLine(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("quantity"),
                        rs.getInt("line_total"), rs.getInt("allocated_discount"), rs.getInt("commission_amount"))).list();
        return jdbc.sql("""
                select o.id, o.number, o.customer_id, o.delivery_name, o.vendor_id, o.status, o.payment_method, o.finished_at, o.out_for_delivery_at, o.incident_hold, o.items_total, o.discount, o.delivery_fee,
                       o.grand_total, o.commission_amount,
                       (select h.acted_by_type from order_status_history h where h.order_id = o.id and h.to_status = 'DELIVERED'
                        order by h.created_at desc limit 1) as delivered_by
                from orders o where o.id = :o""").param("o", orderId)
                .query((rs, n) -> new IncidentOrder(rs.getObject("id", UUID.class), rs.getLong("number"), rs.getObject("customer_id", UUID.class),
                        rs.getString("delivery_name"), rs.getObject("vendor_id", UUID.class), rs.getString("status"), rs.getString("payment_method"),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(), actor(rs.getString("delivered_by")),
                        rs.getTimestamp("out_for_delivery_at") == null ? null : rs.getTimestamp("out_for_delivery_at").toInstant(), rs.getBoolean("incident_hold"),
                        rs.getInt("items_total"), rs.getInt("discount"), rs.getInt("delivery_fee"), rs.getInt("grand_total"), rs.getInt("commission_amount"), lines))
                .optional();
    }

    @Override
    @Transactional
    public void setIncidentHold(UUID orderId, boolean hold) {
        jdbc.sql("update orders set incident_hold = :h where id = :o").param("h", hold).param("o", orderId).update();
    }

    private static String actor(String type) {
        return switch (type == null ? "" : type) {
            case "CUSTOMER" -> "CUSTOMER";
            case "SYSTEM" -> "SYSTEM";
            case "" -> null;
            default -> "SHOP";
        };
    }
}
