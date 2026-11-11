package com.bonbon.backend.marketplace.merchant.repository;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchant.entity.VendorIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorIdentityRepository extends JpaRepository<VendorIdentity, UUID> {
}
