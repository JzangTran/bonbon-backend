package com.bonbon.backend.merchant.repository;

import java.util.UUID;

import com.bonbon.backend.merchant.entity.VendorTaxInfo;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorTaxInfoRepository extends JpaRepository<VendorTaxInfo, UUID> {
}
