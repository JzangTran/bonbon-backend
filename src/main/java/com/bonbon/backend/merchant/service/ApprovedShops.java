package com.bonbon.backend.merchant.service;

import java.util.UUID;

import com.bonbon.backend.common.exception.BusinessException;
import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.Vendor;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The caller's own shop, required to be approved before anything on its menu can be touched. */
@Component
class ApprovedShops {

    private final VendorRepository vendors;

    ApprovedShops(VendorRepository vendors) {
        this.vendors = vendors;
    }

    Vendor of(UUID ownerId) {
        Vendor vendor = vendors.findByOwnerUserId(ownerId).orElseThrow(() -> new BusinessException(HttpStatus.CONFLICT,
                "SHOP_NOT_APPROVED", "Bạn chưa có cửa hàng được duyệt.").withProperty("status", "NONE"));
        if (vendor.getStatus() != VendorStatus.APPROVED) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOP_NOT_APPROVED",
                    "Chỉ cửa hàng đã được duyệt mới quản lý được thực đơn.")
                    .withProperty("status", vendor.getStatus().name());
        }
        return vendor;
    }
}
