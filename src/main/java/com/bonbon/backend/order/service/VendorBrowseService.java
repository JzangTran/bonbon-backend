package com.bonbon.backend.order.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.bonbon.backend.category.CategoryCatalog;
import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.merchant.ShopCatalog;
import com.bonbon.backend.order.dto.VendorPage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Public browsing of shops (browse-vendors-in-area.md, view-vendor-menu.md). */
@Service
public class VendorBrowseService {

    static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 100;

    private final ShopCatalog shops;
    private final CategoryCatalog categories;

    VendorBrowseService(ShopCatalog shops, CategoryCatalog categories) {
        this.shops = shops;
        this.categories = categories;
    }

    /** Shops whose own radius covers the point: open first, nearest first, one page at a time. */
    public VendorPage inArea(double lat, double lng, String query, UUID categoryId, String sort, int page, int size) {
        requirePosition(lat, lng);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "Từ khoá tìm kiếm quá dài.");
        }
        Set<UUID> categoryIds = categoryId == null ? null : categories.subtreeIds(categoryId);
        List<ShopCatalog.Shop> all = shops.shopsInArea(lat, lng, query, categoryIds);
        if ("name".equals(sort)) {
            all = all.stream().sorted(java.util.Comparator.comparing(ShopCatalog.Shop::name, String.CASE_INSENSITIVE_ORDER)).toList();
        } else if (sort != null && !sort.isBlank() && !"distance".equals(sort)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Cách sắp xếp không hợp lệ.");
        }
        int from = Math.min(page * size, all.size());
        int to = Math.min(from + size, all.size());
        return new VendorPage(all.subList(from, to), page, size, all.size());
    }

    public ShopCatalog.ShopMenu menu(UUID shopId, Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Cần cả vĩ độ và kinh độ.");
        }
        if (lat != null) {
            requirePosition(lat, lng);
        }
        return shops.menu(shopId, lat, lng)
                .orElseThrow(() -> BusinessException.notFound("VENDOR_NOT_FOUND", "Không tìm thấy quán."));
    }

    private static void requirePosition(double lat, double lng) {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Toạ độ không hợp lệ.");
        }
    }
}
