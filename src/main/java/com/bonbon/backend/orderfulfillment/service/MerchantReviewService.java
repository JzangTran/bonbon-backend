package com.bonbon.backend.orderfulfillment.service;

import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.merchant.ShopOrdering;
import com.bonbon.backend.order.ReviewViews;
import com.bonbon.backend.order.ShopReviews;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Works out which shop the caller acts for, then hands the review work to the order module. */
@Service
public class MerchantReviewService {

    private final ShopOrdering shops;
    private final ShopReviews reviews;

    MerchantReviewService(ShopOrdering shops, ShopReviews reviews) {
        this.shops = shops;
        this.reviews = reviews;
    }

    public ReviewViews.Page list(CurrentPrincipal caller, boolean unrepliedOnly, int page, int size) {
        return reviews.list(vendorOf(caller), unrepliedOnly, page, size);
    }

    public ReviewViews.Review respond(CurrentPrincipal caller, UUID reviewId, String text) {
        return reviews.respond(vendorOf(caller), reviewId, text, caller.actorType(), caller.id());
    }

    public ReviewViews.Review editResponse(CurrentPrincipal caller, UUID reviewId, String text) {
        return reviews.editResponse(vendorOf(caller), reviewId, text, caller.actorType(), caller.id());
    }

    public void deleteResponse(CurrentPrincipal caller, UUID reviewId) {
        reviews.deleteResponse(vendorOf(caller), reviewId);
    }

    private UUID vendorOf(CurrentPrincipal caller) {
        return shops.approvedVendorOwnedBy(caller.id()).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                "Bạn chưa có cửa hàng được duyệt."));
    }
}
