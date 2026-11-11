package com.bonbon.backend.ordering.order.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.ordering.order.entity.ReviewResponse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewResponseRepository extends JpaRepository<ReviewResponse, UUID> {

    Optional<ReviewResponse> findByReviewId(UUID reviewId);

    List<ReviewResponse> findByReviewIdIn(Collection<UUID> reviewIds);
}
