package com.bonbon.backend.merchantapproval.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One administrator viewing one shop owner's identity documents. */
@Entity
@Table(name = "identity_document_access_log")
public class IdentityAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private UUID adminId;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private Instant accessedAt;

    @Column(columnDefinition = "text", updatable = false)
    private String ip;

    protected IdentityAccess() {
    }

    public IdentityAccess(UUID vendorId, UUID adminId, String ip) {
        this.vendorId = vendorId;
        this.adminId = adminId;
        this.ip = ip;
        this.accessedAt = Instant.now();
    }
}
