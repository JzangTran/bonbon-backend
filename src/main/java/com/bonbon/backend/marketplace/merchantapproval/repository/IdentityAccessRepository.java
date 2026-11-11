package com.bonbon.backend.marketplace.merchantapproval.repository;

import java.util.UUID;

import com.bonbon.backend.marketplace.merchantapproval.entity.IdentityAccess;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityAccessRepository extends JpaRepository<IdentityAccess, UUID> {

    long countByVendorId(UUID vendorId);
}
