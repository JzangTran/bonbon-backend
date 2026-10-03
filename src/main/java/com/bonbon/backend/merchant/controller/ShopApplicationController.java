package com.bonbon.backend.merchant.controller;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.common.web.ClientContext;
import com.bonbon.backend.merchant.dto.ShopApplicationRequests;
import com.bonbon.backend.merchant.dto.ShopApplicationView;
import com.bonbon.backend.merchant.service.ShopApplicationService;
import com.bonbon.backend.merchant.service.ShopApplicationService.FileKind;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The seller's own shop application (open-shop.md). Steps are idempotent draft saves; the shop is always the
 * caller's own, so no id appears in the URL.
 */
@RestController
@RequestMapping("/api/merchant/shop")
@PreAuthorize("hasAuthority('vendor:create')")
class ShopApplicationController {

    private final ShopApplicationService applications;

    ShopApplicationController(ShopApplicationService applications) {
        this.applications = applications;
    }

    /** Status NONE before the first save; otherwise the application with each step's completeness. */
    @GetMapping
    ShopApplicationView current(CurrentPrincipal principal) {
        return applications.view(principal.id());
    }

    @PutMapping("/steps/1")
    ShopApplicationView shopInfo(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step1 request) {
        return applications.saveShopInfo(principal, request);
    }

    @PutMapping("/steps/2")
    ShopApplicationView shipping(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step2 request) {
        return applications.saveShipping(principal, request);
    }

    @PutMapping("/steps/3")
    ShopApplicationView taxAndPayout(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step3 request) {
        return applications.saveTaxAndPayout(principal, request);
    }

    @PutMapping("/steps/4")
    ShopApplicationView identity(CurrentPrincipal principal, @Valid @RequestBody ShopApplicationRequests.Step4 request,
            HttpServletRequest http) {
        return applications.saveIdentity(principal, request, ClientContext.from(http));
    }

    /** {@code kind}: business-license (image or PDF), identity-front, identity-selfie (images). */
    @PostMapping(path = "/files/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ShopApplicationView upload(CurrentPrincipal principal, @PathVariable String kind, @RequestPart("file") MultipartFile file) {
        return applications.uploadFile(principal, fileKind(kind), file);
    }

    @PostMapping("/submit")
    ShopApplicationView submit(CurrentPrincipal principal) {
        return applications.submit(principal);
    }

    private static FileKind fileKind(String kind) {
        return switch (kind) {
            case "business-license" -> FileKind.BUSINESS_LICENSE;
            case "identity-front" -> FileKind.IDENTITY_FRONT;
            case "identity-selfie" -> FileKind.IDENTITY_SELFIE;
            default -> throw BusinessException.notFound("FILE_KIND_UNKNOWN", "Loại tệp không hợp lệ.");
        };
    }
}
