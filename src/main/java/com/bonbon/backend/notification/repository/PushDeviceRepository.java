package com.bonbon.backend.notification.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.notification.entity.PushDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushDeviceRepository extends JpaRepository<PushDevice, UUID> {

    Optional<PushDevice> findByKindAndToken(String kind, String token);

    @Query("select d from PushDevice d where d.userId = :userId and d.status = 'ACTIVE'")
    List<PushDevice> findActiveByUser(@Param("userId") UUID userId);
}
