package com.bonbon.backend.merchant.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.bonbon.backend.merchant.entity.Option;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OptionRepository extends JpaRepository<Option, UUID> {

    @Query("select o from Option o where o.groupId in :groupIds and o.status <> 'ARCHIVED' order by o.displayOrder, o.name")
    List<Option> findLive(@Param("groupIds") Collection<UUID> groupIds);
}
