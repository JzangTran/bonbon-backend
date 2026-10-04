package com.bonbon.backend.merchant.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.OptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptionGroupRepository extends JpaRepository<OptionGroup, UUID> {

    @Query("select g from OptionGroup g where g.vendorId = :vendorId and g.status = 'ACTIVE' order by g.name")
    List<OptionGroup> findActiveByVendor(@Param("vendorId") UUID vendorId);

    @Query("select g from OptionGroup g where g.id = :id and g.vendorId = :vendorId and g.status = 'ACTIVE'")
    Optional<OptionGroup> findActive(@Param("id") UUID id, @Param("vendorId") UUID vendorId);
}
