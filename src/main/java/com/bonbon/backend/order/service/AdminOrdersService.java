package com.bonbon.backend.order.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.order.AdminOrders;
import com.bonbon.backend.order.OrderStatus;
import com.bonbon.backend.order.ShopOrders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminOrdersService implements AdminOrders {

    private final JdbcClient jdbc;

    AdminOrdersService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminOrder> byNumber(long number) {
        return jdbc.sql("select id from orders where number = :n").param("n", number).query(UUID.class).optional().flatMap(this::get);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AdminOrder> get(UUID orderId) {
        return jdbc.sql("""
                select id, number, status, vendor_id, vendor_name, customer_id, delivery_name, delivery_phone, delivery_address, note, payment_method,
                       payment_status, items_total, discount, delivery_fee, grand_total, commission_amount, placed_at, confirmed_at, out_for_delivery_at, finished_at
                from orders where id = :id""").param("id", orderId)
                .query((rs, n) -> new AdminOrder(rs.getObject("id", UUID.class), rs.getLong("number"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getObject("vendor_id", UUID.class), rs.getString("vendor_name"), rs.getObject("customer_id", UUID.class), rs.getString("delivery_name"),
                        rs.getString("delivery_phone"), rs.getString("delivery_address"), rs.getString("note"), rs.getString("payment_method"),
                        rs.getString("payment_status"), lines(orderId), rs.getInt("items_total"), rs.getInt("discount"), rs.getInt("delivery_fee"),
                        rs.getInt("grand_total"), rs.getInt("commission_amount"), rs.getTimestamp("placed_at").toInstant(),
                        rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
                        rs.getTimestamp("out_for_delivery_at") == null ? null : rs.getTimestamp("out_for_delivery_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(), timeline(orderId)))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminOrderLine> ofCustomer(UUID customerId, int limit) {
        return jdbc.sql("select id, number, status, vendor_name, grand_total, placed_at from orders where customer_id = :c order by placed_at desc limit :l")
                .param("c", customerId).param("l", limit)
                .query((rs, n) -> new AdminOrderLine(rs.getObject("id", UUID.class), rs.getLong("number"), OrderStatus.valueOf(rs.getString("status")),
                        rs.getString("vendor_name"), rs.getInt("grand_total"), rs.getTimestamp("placed_at").toInstant()))
                .list();
    }

    private List<ShopOrders.Line> lines(UUID orderId) {
        Map<UUID, List<ShopOrders.LineOption>> options = new LinkedHashMap<>();
        jdbc.sql("""
                select o.order_item_id, o.group_name, o.option_name, o.price_delta from order_item_options o
                join order_items i on i.id = o.order_item_id where i.order_id = :id order by o.order_item_id, o.position""").param("id", orderId)
                .query((rs, n) -> {
                    options.computeIfAbsent(rs.getObject("order_item_id", UUID.class), k -> new ArrayList<>())
                            .add(new ShopOrders.LineOption(rs.getString("group_name"), rs.getString("option_name"), rs.getInt("price_delta")));
                    return 0;
                }).list();
        return jdbc.sql("select id, name, quantity, unit_price, line_total, note from order_items where order_id = :id order by position").param("id", orderId)
                .query((rs, n) -> new ShopOrders.Line(rs.getString("name"), rs.getInt("quantity"), rs.getInt("unit_price"), rs.getInt("line_total"), rs.getString("note"),
                        options.getOrDefault(rs.getObject("id", UUID.class), List.of())))
                .list();
    }

    private List<ShopOrders.Step> timeline(UUID orderId) {
        return jdbc.sql("select from_status, to_status, acted_by_type, reason, created_at from order_status_history where order_id = :id order by created_at, id")
                .param("id", orderId)
                .query((rs, n) -> new ShopOrders.Step(rs.getString("from_status") == null ? null : OrderStatus.valueOf(rs.getString("from_status")),
                        OrderStatus.valueOf(rs.getString("to_status")), actor(rs.getString("acted_by_type")), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static String actor(String type) {
        return switch (type) {
            case "SHOP_ACCOUNT", "MAIN_ACCOUNT", "MEMBER" -> "SHOP";
            default -> type;
        };
    }
}
