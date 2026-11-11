package com.bonbon.backend.identity.legal.repository;

import java.util.List;
import java.util.UUID;

import com.bonbon.backend.identity.legal.entity.ConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    List<ConsentRecord> findByPrincipalId(UUID principalId);
}
