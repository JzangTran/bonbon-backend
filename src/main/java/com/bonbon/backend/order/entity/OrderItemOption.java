package com.bonbon.backend.order.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An option the customer chose on a line, copied so a later rename or removal changes nothing. */
@Entity
@Table(name = "order_item_options")
public class OrderItemOption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
    private OrderItem item;

    @Column(nullable = false)
    private int position;

    @Column(name = "group_name", nullable = false, columnDefinition = "text")
    private String groupName;

    @Column(name = "option_name", nullable = false, columnDefinition = "text")
    private String optionName;

    @Column(name = "price_delta", nullable = false)
    private int priceDelta;

    protected OrderItemOption() {
    }

    OrderItemOption(OrderItem item, int position, String groupName, String optionName, int priceDelta) {
        this.item = item;
        this.position = position;
        this.groupName = groupName;
        this.optionName = optionName;
        this.priceDelta = priceDelta;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getOptionName() {
        return optionName;
    }

    public int getPriceDelta() {
        return priceDelta;
    }
}
