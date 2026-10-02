package com.bonbon.backend.legal.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.bonbon.backend.legal.DocumentType;
import com.bonbon.backend.legal.entity.LegalDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LegalDocumentRepository extends JpaRepository<LegalDocument, UUID> {

    /** The version in force: published, already effective, highest version wins. */
    @Query("""
            select d from LegalDocument d
            where d.type = :type and d.language = 'vi' and d.publishedAt <= :now and d.effectiveAt <= :now
            order by d.version desc limit 1
            """)
    Optional<LegalDocument> findCurrent(@Param("type") DocumentType type, @Param("now") Instant now);
}
