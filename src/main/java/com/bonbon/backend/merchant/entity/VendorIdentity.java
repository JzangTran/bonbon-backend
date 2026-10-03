package com.bonbon.backend.merchant.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Wizard step 4: the owner's identity document. The number is stored encrypted with its last 4 digits in
 * clear for display; photos are keys in the private bucket.
 */
@Entity
@Table(name = "vendor_identity")
public class VendorIdentity {

    public enum DocType { CCCD, CMND }

    @Id
    @Column(name = "vendor_id")
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", columnDefinition = "text")
    private DocType docType;

    @Column(name = "doc_number_encrypted", columnDefinition = "text")
    private String docNumberEncrypted;

    @Column(name = "doc_number_last4", columnDefinition = "text")
    private String docNumberLast4;

    @Column(name = "full_name", columnDefinition = "text")
    private String fullName;

    @Column(name = "front_file_key", columnDefinition = "text")
    private String frontFileKey;

    @Column(name = "selfie_file_key", columnDefinition = "text")
    private String selfieFileKey;

    @Column(name = "accuracy_confirmed_at")
    private Instant accuracyConfirmedAt;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "identity_consent_at")
    private Instant identityConsentAt;

    protected VendorIdentity() {
    }

    public VendorIdentity(UUID vendorId) {
        this.vendorId = vendorId;
    }

    public void setDocument(DocType type, String fullName) {
        this.docType = type;
        this.fullName = fullName;
    }

    public void setDocNumber(String encrypted, String last4) {
        this.docNumberEncrypted = encrypted;
        this.docNumberLast4 = last4;
    }

    public void setAccuracyConfirmedAt(Instant at) {
        this.accuracyConfirmedAt = at;
    }

    public void setTermsAcceptedAt(Instant at) {
        this.termsAcceptedAt = at;
    }

    public void setIdentityConsentAt(Instant at) {
        this.identityConsentAt = at;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public DocType getDocType() {
        return docType;
    }

    public String getDocNumberEncrypted() {
        return docNumberEncrypted;
    }

    public String getDocNumberLast4() {
        return docNumberLast4;
    }

    public String getFullName() {
        return fullName;
    }

    public String getFrontFileKey() {
        return frontFileKey;
    }

    public void setFrontFileKey(String frontFileKey) {
        this.frontFileKey = frontFileKey;
    }

    public String getSelfieFileKey() {
        return selfieFileKey;
    }

    public void setSelfieFileKey(String selfieFileKey) {
        this.selfieFileKey = selfieFileKey;
    }

    public Instant getAccuracyConfirmedAt() {
        return accuracyConfirmedAt;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public Instant getIdentityConsentAt() {
        return identityConsentAt;
    }
}
