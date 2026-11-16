package com.bonbon.backend.ordering.order.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.identity.legal.PersonalDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The person's orders (with what they ordered and where it went) and the reviews they wrote, for the personal-data export. */
@Component
class OrderPersonalData implements PersonalDataSource {

    private final JdbcClient jdbc;

    OrderPersonalData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String section() {
        return "orders";
    }

    @Override
    public Object dataOf(UUID userId) {
        List<Map<String, Object>> orders = jdbc.sql("""
                select o.id, o.number, o.vendor_name, o.status, o.payment_method, o.grand_total, o.delivery_name, o.delivery_phone, o.delivery_address,
                       o.placed_at, (select string_agg(i.quantity || ' x ' || i.name, '; ') from ordering.order_items i where i.order_id = o.id) as items
                from ordering.orders o where o.customer_id = :u order by o.placed_at desc""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("number", rs.getLong("number"));
                    o.put("shop", rs.getString("vendor_name"));
                    o.put("status", rs.getString("status"));
                    o.put("payment", rs.getString("payment_method"));
                    o.put("total", rs.getInt("grand_total"));
                    o.put("items", rs.getString("items"));
                    o.put("deliveryName", rs.getString("delivery_name"));
                    o.put("deliveryPhone", rs.getString("delivery_phone"));
                    o.put("deliveryAddress", rs.getString("delivery_address"));
                    o.put("placedAt", rs.getTimestamp("placed_at") == null ? null : rs.getTimestamp("placed_at").toInstant());
                    return o;
                }).list();
        List<Map<String, Object>> reviews = jdbc.sql("""
                select r.rating, r.comment, r.created_at, o.number from ordering.reviews r join ordering.orders o on o.id = r.order_id where r.customer_id = :u""")
                .param("u", userId).query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("orderNumber", rs.getLong("number"));
                    r.put("rating", rs.getInt("rating"));
                    r.put("comment", rs.getString("comment"));
                    r.put("createdAt", rs.getTimestamp("created_at").toInstant());
                    return r;
                }).list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orders", orders);
        out.put("reviews", reviews);
        return out;
    }
}
