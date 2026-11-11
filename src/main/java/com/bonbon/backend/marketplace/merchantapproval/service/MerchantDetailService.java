package com.bonbon.backend.marketplace.merchantapproval.service;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchant.ShopApplications;
import com.bonbon.backend.marketplace.merchantapproval.dto.ReviewDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A shop as an administrator sees it after the review (view-merchant-detail.md): the application with its decisions, every
 * suspension, and the shops that look like it. The identity documents stay behind their own audited endpoint.
 */
@Service
public class MerchantDetailService {

    private final ShopReviewService reviews;
    private final SuspensionService suspensions;
    private final ShopApplications shops;

    MerchantDetailService(ShopReviewService reviews, SuspensionService suspensions, ShopApplications shops) {
        this.reviews = reviews;
        this.suspensions = suspensions;
        this.shops = shops;
    }

    @Transactional(readOnly = true)
    public ReviewDtos.MerchantDetail detail(UUID vendorId) {
        ReviewDtos.Application application = reviews.application(vendorId);
        return new ReviewDtos.MerchantDetail(application.shop(), application.history(), suspensions.history(vendorId).items(), shops.duplicatesOf(vendorId));
    }
}
