package com.bonbon.backend.marketplace.category.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.marketplace.category.entity.DisplayCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DisplayCategoryRepository extends JpaRepository<DisplayCategory, UUID> {

    List<DisplayCategory> findAllByOrderBySortOrderAscNameAsc();

    List<DisplayCategory> findByActiveTrueOrderBySortOrderAscNameAsc();

    @Query("select coalesce(max(d.sortOrder), 0) from DisplayCategory d")
    int maxSortOrder();
}
