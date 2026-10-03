package com.bonbon.backend.merchant.controller;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.dto.ShopProfileRequests;
import com.bonbon.backend.merchant.service.ShopProfileService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An approved shop editing itself; reading stays on GET /api/merchant/shop. */
@RestController
@RequestMapping("/api/merchant/shop")
@PreAuthorize("hasAuthority('vendor:write')")
class ShopProfileController {

    private final ShopProfileService profiles;

    ShopProfileController(ShopProfileService profiles) {
        this.profiles = profiles;
    }

    /** Partial update; a changed address returns the shop to PENDING until approved again. */
    @PatchMapping
    ShopApplicationView update(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.Update request) {
        return profiles.update(principal, request);
    }

    /** Full replacement of the weekly schedule. */
    @PutMapping("/opening-hours")
    ShopApplicationView openingHours(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.OpeningHours request) {
        return profiles.replaceOpeningHours(principal, request);
    }

    @PutMapping("/accepting-orders")
    ShopApplicationView acceptingOrders(CurrentPrincipal principal, @Valid @RequestBody ShopProfileRequests.AcceptingOrders request) {
        return profiles.setAcceptingOrders(principal, request.accepting());
    }
}
