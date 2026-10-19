package com.bonbon.backend.servicearea.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.servicearea.entity.ServiceArea;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAreaRepository extends JpaRepository<ServiceArea, UUID> {

    @Query("select a from ServiceArea a order by lower(a.name)")
    List<ServiceArea> findAllByName();

    List<ServiceArea> findByActiveTrue();

    @Query("select count(a) > 0 from ServiceArea a where lower(trim(a.name)) = lower(trim(:name)) and a.id <> :exceptId")
    boolean nameTaken(@Param("name") String name, @Param("exceptId") UUID exceptId);
}
