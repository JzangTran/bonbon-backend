package com.bonbon.backend.order.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.order.OrderSales;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderSalesService implements OrderSales {

    private final JdbcClient jdbc;

    OrderSalesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bucket> revenue(UUID vendorId, Instant from, Instant to, String granularity) {
        // date_trunc takes its unit as text; the caller has already limited it to day, week or month.
        return jdbc.sql("""
                select date_trunc(:g, finished_at at time zone 'Asia/Ho_Chi_Minh')::date as start, count(*) as orders, sum(grand_total) as revenue
                from orders where vendor_id = :v and status = 'DELIVERED' and finished_at >= :from and finished_at < :to
                group by 1 order by 1""")
                .param("g", granularity).param("v", vendorId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new Bucket(rs.getDate("start").toLocalDate(), rs.getLong("orders"), rs.getLong("revenue"))).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopDish> topDishes(UUID vendorId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                select i.menu_item_id, (array_agg(i.name order by o.finished_at desc))[1] as name, sum(i.quantity) as quantity, sum(i.line_total) as revenue
                from order_items i join orders o on o.id = i.order_id
                where o.vendor_id = :v and o.status = 'DELIVERED' and o.finished_at >= :from and o.finished_at < :to
                group by i.menu_item_id order by sum(i.quantity) desc, sum(i.line_total) desc, i.menu_item_id limit :limit""")
                .param("v", vendorId).param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query((rs, n) -> new TopDish(rs.getObject("menu_item_id", UUID.class), rs.getString("name"), rs.getLong("quantity"), rs.getLong("revenue")))
                .list();
    }
}
