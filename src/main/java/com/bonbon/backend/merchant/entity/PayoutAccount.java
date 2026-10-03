package com.bonbon.backend.merchant.entity;

import java.time.Instant;
import java.util.UUID;

import com.bonbon.backend.common.persistence.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Where the shop is paid (open-shop.md, Payout account). Before approval the wizard edits the one ACTIVE
 * row in place, and fields may be missing while it is a draft. Changing it after approval (24-hour hold,
 * notifications) is a separate flow.
 */
@Entity
@Table(name = "vendor_payout_accounts")
public class PayoutAccount {

    public enum Status { ACTIVE, PENDING_HOLD, REPLACED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "bank_name", columnDefinition = "text")
    private String bankName;

    @Column(name = "account_number_encrypted", columnDefinition = "text")
    private String accountNumberEncrypted;

    @Column(name = "account_last4", columnDefinition = "text")
    private String accountLast4;

    @Column(name = "account_holder_name", columnDefinition = "text")
    private String accountHolderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private Status status = Status.ACTIVE;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_type", nullable = false, columnDefinition = "text")
    private ActorType createdByType;

    @Column(name = "created_by_id")
    private UUID createdById;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PayoutAccount() {
    }

    public PayoutAccount(UUID vendorId, ActorType createdByType, UUID createdById) {
        this.vendorId = vendorId;
        this.createdByType = createdByType;
        this.createdById = createdById;
        this.createdAt = Instant.now();
        this.effectiveFrom = createdAt;
    }

    public void update(String bankName, String accountHolderName) {
        this.bankName = bankName;
        this.accountHolderName = accountHolderName;
    }

    public void setAccountNumber(String encrypted, String last4) {
        this.accountNumberEncrypted = encrypted;
        this.accountLast4 = last4;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public String getBankName() {
        return bankName;
    }

    public String getAccountNumberEncrypted() {
        return accountNumberEncrypted;
    }

    public String getAccountLast4() {
        return accountLast4;
    }

    public String getAccountHolderName() {
        return accountHolderName;
    }

    public Status getStatus() {
        return status;
    }
}
