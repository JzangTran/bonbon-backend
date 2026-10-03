package com.bonbon.backend.legal.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only evidence of one decision about one purpose; never updated. */
@Entity
@Table(name = "consent_records")
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "principal_type", nullable = false, updatable = false, columnDefinition = "text")
    private String principalType;

    @Column(name = "principal_id", nullable = false, updatable = false)
    private UUID principalId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String purpose;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Column(nullable = false, updatable = false)
    private boolean granted;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String channel;

    @Column(name = "app_version", updatable = false, columnDefinition = "text")
    private String appVersion;

    @Column(updatable = false, columnDefinition = "text")
    private String ip;

    protected ConsentRecord() {
    }

    public ConsentRecord(String principalType, UUID principalId, String purpose, UUID documentId, boolean granted,
            String channel, String appVersion, String ip) {
        this.principalType = principalType;
        this.principalId = principalId;
        this.purpose = purpose;
        this.documentId = documentId;
        this.granted = granted;
        this.at = Instant.now();
        this.channel = channel;
        this.appVersion = appVersion;
        this.ip = ip;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public boolean isGranted() {
        return granted;
    }
}
