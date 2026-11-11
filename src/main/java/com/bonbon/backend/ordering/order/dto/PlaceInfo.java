package com.bonbon.backend.ordering.order.dto;

import java.util.List;

import com.bonbon.backend.marketplace.merchant.ShopCatalog;

/**
 * A shop as a place: its schedule, and the average number of orders in each hour of the day (24 values, 00:00 first, Vietnam
 * time) on a working day and on a weekend day.
 */
public record PlaceInfo(ShopCatalog.ShopSchedule schedule, List<Double> weekday, List<Double> weekend) {
}
