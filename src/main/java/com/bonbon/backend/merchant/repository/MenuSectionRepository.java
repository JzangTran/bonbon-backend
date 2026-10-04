package com.bonbon.backend.merchant.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.MenuSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuSectionRepository extends JpaRepository<MenuSection, UUID> {

    List<MenuSection> findByVendorIdOrderBySortOrderAscNameAsc(UUID vendorId);

    Optional<MenuSection> findByIdAndVendorId(UUID id, UUID vendorId);

    @Query("select coalesce(max(s.sortOrder), 0) from MenuSection s where s.vendorId = :vendorId")
    int maxSortOrder(@Param("vendorId") UUID vendorId);

    @Query("""
            select count(s) > 0 from MenuSection s
            where s.vendorId = :vendorId and lower(trim(s.name)) = lower(trim(:name)) and s.id <> :exceptId""")
    boolean nameTaken(@Param("vendorId") UUID vendorId, @Param("name") String name, @Param("exceptId") UUID exceptId);
}
