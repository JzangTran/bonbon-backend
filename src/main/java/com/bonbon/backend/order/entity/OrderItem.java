package com.bonbon.backend.order.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/** A line of an order: the dish name and unit price (options included) as they were when it was placed. */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(name = "menu_item_id", nullable = false, updatable = false)
    private UUID menuItemId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "unit_price", nullable = false)
    private int unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false)
    private int lineTotal;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionRate;

    @Column(name = "allocated_discount", nullable = false)
    private int allocatedDiscount;

    @Column(name = "commission_amount", nullable = false)
    private int commissionAmount;

    @OneToMany(mappedBy = "item", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<OrderItemOption> options = new ArrayList<>();

    protected OrderItem() {
    }

    public OrderItem(UUID menuItemId, String name, int unitPrice, int quantity, String note, UUID categoryId,
            BigDecimal commissionRate, int commissionAmount) {
        this.menuItemId = menuItemId;
        this.name = name;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = unitPrice * quantity;
        this.note = note;
        this.categoryId = categoryId;
        this.commissionRate = commissionRate;
        this.commissionAmount = commissionAmount;
    }

    void attachTo(Order order, int position) {
        this.order = order;
        this.position = position;
    }

    public void addOption(String groupName, String optionName, int priceDelta) {
        options.add(new OrderItemOption(this, options.size() + 1, groupName, optionName, priceDelta));
    }

    public UUID getId() {
        return id;
    }

    public UUID getMenuItemId() {
        return menuItemId;
    }

    public String getName() {
        return name;
    }

    public int getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getLineTotal() {
        return lineTotal;
    }

    public String getNote() {
        return note;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public int getAllocatedDiscount() {
        return allocatedDiscount;
    }

    public int getCommissionAmount() {
        return commissionAmount;
    }

    public List<OrderItemOption> getOptions() {
        return options;
    }
}
