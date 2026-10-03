package com.bonbon.backend.merchantapproval.repository;

import java.util.UUID;

import com.bonbon.backend.merchantapproval.entity.IdentityAccess;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentityAccessRepository extends JpaRepository<IdentityAccess, UUID> {

    long countByVendorId(UUID vendorId);
}
