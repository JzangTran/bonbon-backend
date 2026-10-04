package com.bonbon.backend.order.controller;

import java.util.UUID;

import com.bonbon.backend.merchant.ShopCatalog;
import com.bonbon.backend.order.dto.VendorPage;
import com.bonbon.backend.order.service.VendorBrowseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public: guests browse shops and menus before logging in. */
@RestController
@RequestMapping("/api/vendors")
class VendorController {

    private final VendorBrowseService browse;

    VendorController(VendorBrowseService browse) {
        this.browse = browse;
    }

    /** {@code sort}: {@code distance} (default; open shops first) or {@code name}. */
    @GetMapping
    VendorPage inArea(@RequestParam double lat, @RequestParam double lng, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return browse.inArea(lat, lng, q, categoryId, sort, page, size);
    }

    /** Sold-out dishes are returned with {@code soldOut: true}; the stock number is never exposed. */
    @GetMapping("/{id}/menu")
    ShopCatalog.ShopMenu menu(@PathVariable UUID id, @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng) {
        return browse.menu(id, lat, lng);
    }
}
