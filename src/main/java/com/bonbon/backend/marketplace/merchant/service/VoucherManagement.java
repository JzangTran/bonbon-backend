package com.bonbon.backend.marketplace.merchant.service;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.common.security.CurrentPrincipal;
import com.bonbon.backend.marketplace.merchant.dto.VoucherViews;

/** The seller's side of vouchers, for this module's controller and shop closing. */
public interface VoucherManagement {

    List<VoucherViews.Item> list(CurrentPrincipal owner);

    VoucherViews.Item create(CurrentPrincipal owner, VoucherViews.Create request);

    VoucherViews.Item update(CurrentPrincipal owner, UUID id, VoucherViews.Update request);

    VoucherViews.Item setStatus(CurrentPrincipal owner, UUID id, String status);

    /** A closing shop offers nothing new (close-shop.md). */
    void disableAll(UUID vendorId);
}
