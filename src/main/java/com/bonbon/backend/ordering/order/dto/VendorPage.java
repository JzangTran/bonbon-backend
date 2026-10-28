package com.bonbon.backend.ordering.order.dto;

import java.util.List;

import com.bonbon.backend.marketplace.merchant.ShopCatalog;
import io.swagger.v3.oas.annotations.media.Schema;

/** One page of shops in an area; {@code total} counts every match, not just this page. */
@Schema(name = "VendorPage")
public record VendorPage(List<ShopCatalog.Shop> items, int page, int size, int total) {
}
