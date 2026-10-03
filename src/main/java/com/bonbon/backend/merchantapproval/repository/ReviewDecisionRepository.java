package com.bonbon.backend.merchantapproval.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchantapproval.entity.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    List<ReviewDecision> findByVendorIdOrderByDecidedAtDesc(UUID vendorId);

    boolean existsByVendorIdAndDecision(UUID vendorId, ReviewDecision.Decision decision);
}
