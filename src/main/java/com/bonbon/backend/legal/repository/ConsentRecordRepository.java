package com.bonbon.backend.legal.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.legal.entity.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findByPrincipalId(UUID principalId);
}
