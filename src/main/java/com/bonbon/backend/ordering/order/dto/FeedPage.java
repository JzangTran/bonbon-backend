package com.bonbon.backend.ordering.order.dto;

import java.util.List;

import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of the home page: up to 10 shops with up to 10 dishes each. {@code dealMaxPercent} is the biggest discount among the
 * shops listed (for "Giảm đến X%"), 0 when none.
 */
@Schema(name = "HomeFeedPage")
public record FeedPage(List<ShopCatalog.FeedCard> items, int page, int size, boolean hasMore, int total, int dealMaxPercent) {
}
