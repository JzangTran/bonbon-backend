package com.bonbon.backend.account.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.account.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    @Query("select a from Address a where a.customerId = :customerId order by a.defaultAddress desc, a.createdAt desc")
    List<Address> findForCustomer(@Param("customerId") UUID customerId);

    Optional<Address> findByIdAndCustomerId(UUID id, UUID customerId);

    long countByCustomerId(UUID customerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Address a set a.defaultAddress = false where a.customerId = :customerId and a.defaultAddress = true")
    void clearDefault(@Param("customerId") UUID customerId);
}
