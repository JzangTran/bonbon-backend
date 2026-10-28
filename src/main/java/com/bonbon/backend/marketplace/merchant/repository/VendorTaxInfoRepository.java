package com.bonbon.backend.marketplace.merchant.repository;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchant.entity.VendorTaxInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorTaxInfoRepository extends JpaRepository<VendorTaxInfo, UUID> {
}
