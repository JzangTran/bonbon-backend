package com.bonbon.backend.merchant.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bonbon.backend.merchant.ShopNames;
import com.bonbon.backend.merchant.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShopNamesService implements ShopNames {

    private final VendorRepository vendors;

    ShopNamesService(VendorRepository vendors) {
        this.vendors = vendors;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> vendorIds) {
        Map<UUID, String> result = new HashMap<>();
        if (!vendorIds.isEmpty()) {
            vendors.findAllById(vendorIds).forEach(v -> result.put(v.getId(), v.getName()));
        }
        return result;
    }
}
