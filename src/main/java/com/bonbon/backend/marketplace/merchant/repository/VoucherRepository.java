package com.bonbon.backend.marketplace.merchant.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.marketplace.merchant.entity.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, UUID> {

    List<Voucher> findByVendorIdOrderByCreatedAtDesc(UUID vendorId);

    @Query("select v from Voucher v where v.vendorId = :vendorId and upper(v.code) = upper(:code)")
    Optional<Voucher> findByCode(@Param("vendorId") UUID vendorId, @Param("code") String code);

    @Query("""
            select v from Voucher v
            where v.vendorId = :vendorId and v.status = 'ACTIVE' and v.validFrom <= :now and v.validTo > :now
              and (v.usageLimit is null or v.usedCount < v.usageLimit)
            order by v.validTo, v.code""")
    List<Voucher> findOffers(@Param("vendorId") UUID vendorId, @Param("now") Instant now);

    /** The race for the last use is settled here: the row is only updated while a use is left. */
    @Modifying(clearAutomatically = false)
    @Query(value = """
            update marketplace.vouchers set used_count = used_count + 1, updated_at = now()
            where id = :id and status = 'ACTIVE' and (usage_limit is null or used_count < usage_limit)""", nativeQuery = true)
    int takeUse(@Param("id") UUID id);

    @Modifying(clearAutomatically = false)
    @Query(value = "update marketplace.vouchers set used_count = greatest(used_count - 1, 0), updated_at = now() where id = :id", nativeQuery = true)
    int giveUseBack(@Param("id") UUID id);

    @Modifying
    @Query(value = "update marketplace.vouchers set status = 'DISABLED', updated_at = now() where vendor_id = :vendorId and status = 'ACTIVE'", nativeQuery = true)
    int disableAll(@Param("vendorId") UUID vendorId);
}
