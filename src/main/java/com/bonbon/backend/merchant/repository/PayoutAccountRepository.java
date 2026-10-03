package com.bonbon.backend.merchant.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.PayoutAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    @Query("select p from PayoutAccount p where p.vendorId = :vendorId and p.status = com.bonbon.backend.merchant.entity.PayoutAccount.Status.ACTIVE")
    Optional<PayoutAccount> findActive(@Param("vendorId") UUID vendorId);
}
