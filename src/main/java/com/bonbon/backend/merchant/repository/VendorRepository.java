package com.bonbon.backend.merchant.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.merchant.VendorStatus;
import com.bonbon.backend.merchant.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByOwnerUserId(UUID ownerUserId);

    /** {@code pattern} is a lower-case LIKE pattern ({@code %} matches everything). */
    @Query("""
            select v from Vendor v
            where v.status in :statuses
              and (lower(coalesce(v.name, '')) like :pattern or lower(coalesce(v.ward, '')) like :pattern
                   or lower(coalesce(v.province, '')) like :pattern)
            order by v.submittedAt asc nulls last""")
    List<Vendor> search(@Param("statuses") Collection<VendorStatus> statuses, @Param("pattern") String pattern);
}
