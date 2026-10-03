package com.bonbon.backend.merchant.repository;

import java.util.UUID;

import com.bonbon.backend.merchant.entity.VendorIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorIdentityRepository extends JpaRepository<VendorIdentity, UUID> {
}
