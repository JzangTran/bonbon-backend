package com.bonbon.backend.merchant.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByOwnerUserId(UUID ownerUserId);
}
