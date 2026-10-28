package com.bonbon.backend.identity.legal.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.identity.legal.DocumentType;
import com.bonbon.backend.identity.legal.LegalDocumentView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only here; versions are created and published through {@code LegalAdminService}. */
@Entity
@Table(name = "legal_documents")
public class LegalDocument {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private DocumentType type;

    @Column(nullable = false, columnDefinition = "text")
    private String language;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "requires_reacceptance", nullable = false)
    private boolean requiresReacceptance;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(nullable = false, columnDefinition = "text")
    private String status;

    protected LegalDocument() {
    }

    public UUID getId() {
        return id;
    }

    public DocumentType getType() {
        return type;
    }

    public LegalDocumentView toView() {
        return new LegalDocumentView(id, type, version, title, summary, content, effectiveAt);
    }
}
