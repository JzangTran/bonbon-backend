package com.bonbon.backend.order.repository;

import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.order.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Optional<Review> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    /** What everyone sees of a shop: hidden reviews are left out. */
    @Query("select r from Review r where r.vendorId = :vendorId and r.hiddenAt is null")
    Page<Review> findVisibleByVendor(@Param("vendorId") UUID vendorId, Pageable pageable);

    /** The shop's own list: visible reviews, or only the ones it has not replied to yet when {@code all} is false. */
    @Query("""
            select r from Review r where r.vendorId = :vendorId and r.hiddenAt is null
              and (:all = true or not exists (select 1 from ReviewResponse p where p.reviewId = r.id))""")
    Page<Review> findForShop(@Param("vendorId") UUID vendorId, @Param("all") boolean all, Pageable pageable);
}
