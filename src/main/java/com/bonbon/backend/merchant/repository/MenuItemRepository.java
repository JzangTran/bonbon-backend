package com.bonbon.backend.merchant.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    @Query("select i from MenuItem i where i.vendorId = :vendorId and i.archivedAt is null order by i.sortOrder, i.name")
    List<MenuItem> findActiveByVendor(@Param("vendorId") UUID vendorId);

    @Query("select i from MenuItem i where i.id = :id and i.vendorId = :vendorId and i.archivedAt is null")
    Optional<MenuItem> findActive(@Param("id") UUID id, @Param("vendorId") UUID vendorId);

    @Query("select count(i) > 0 from MenuItem i where i.sectionId = :sectionId and i.archivedAt is null")
    boolean sectionHasActiveItems(@Param("sectionId") UUID sectionId);

    /** Archived dishes count too: their category row is still referenced. */
    long countByCategoryId(UUID categoryId);

    @Query("select coalesce(max(i.sortOrder), 0) from MenuItem i where i.sectionId = :sectionId")
    int maxSortOrder(@Param("sectionId") UUID sectionId);
}
