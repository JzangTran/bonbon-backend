package com.bonbon.backend.merchantapproval.controller;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.merchant.ShopApplications;
import com.bonbon.backend.merchantapproval.dto.ReviewDtos;
import com.bonbon.backend.merchantapproval.service.ShopReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
class ShopReviewController {

    private final ShopReviewService reviews;

    ShopReviewController(ShopReviewService reviews) {
        this.reviews = reviews;
    }

    /** Submitted applications, oldest first; drafts never appear. */
    @GetMapping("/merchant-approval/requests")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    List<ReviewDtos.QueueRow> queue(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return reviews.queue(status, q);
    }

    /** Shop, shipping, tax and the masked payout account, with earlier decisions; no identity documents. */
    @GetMapping("/merchant-approval/requests/{vendorId}")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    ReviewDtos.Application application(@PathVariable UUID vendorId) {
        return reviews.application(vendorId);
    }

    /** Separate permission, and every call is audit-logged. Photo URLs expire after 5 minutes. */
    @GetMapping("/merchant-approval/requests/{vendorId}/identity")
    @PreAuthorize("hasAuthority('merchant-approval:read-identity')")
    ShopApplications.IdentityDocuments identity(CurrentPrincipal principal, @PathVariable UUID vendorId, HttpServletRequest http) {
        return reviews.identity(principal, vendorId, ClientContext.from(http).ip());
    }

    @PostMapping("/merchant-approval/requests/{vendorId}/approve")
    @PreAuthorize("hasAuthority('merchant-approval:decide')")
    ShopApplications.Decided approve(CurrentPrincipal principal, @PathVariable UUID vendorId) {
        return reviews.approve(principal, vendorId);
    }

    @PostMapping("/merchant-approval/requests/{vendorId}/reject")
    @PreAuthorize("hasAuthority('merchant-approval:decide')")
    ShopApplications.Decided reject(CurrentPrincipal principal, @PathVariable UUID vendorId,
            @Valid @RequestBody ReviewDtos.RejectRequest request) {
        return reviews.reject(principal, vendorId, request.reason());
    }

    @GetMapping("/settings/delivery-radius-cap")
    @PreAuthorize("hasAuthority('merchant-approval:read')")
    ReviewDtos.RadiusCap radiusCap() {
        return reviews.radiusCap();
    }

    @PutMapping("/settings/delivery-radius-cap")
    @PreAuthorize("hasAuthority('merchant-approval:write-settings')")
    ReviewDtos.RadiusCap setRadiusCap(CurrentPrincipal principal, @Valid @RequestBody ReviewDtos.RadiusCap request) {
        return reviews.setRadiusCap(principal, request.maxRadiusKm());
    }
}
