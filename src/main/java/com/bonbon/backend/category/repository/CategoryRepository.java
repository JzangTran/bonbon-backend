package com.bonbon.backend.category.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.category.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("select c from Category c order by c.level, c.sortOrder, c.name")
    List<Category> findAllInTreeOrder();

    boolean existsByParentId(UUID parentId);

    @Query("select coalesce(max(c.sortOrder), 0) from Category c where c.parentId = :parentId")
    int maxSortOrder(@Param("parentId") UUID parentId);

    @Query("""
            select count(c) > 0 from Category c
            where c.parentId = :parentId and lower(trim(c.name)) = lower(trim(:name)) and c.id <> :exceptId""")
    boolean siblingNameTaken(@Param("parentId") UUID parentId, @Param("name") String name, @Param("exceptId") UUID exceptId);
}
