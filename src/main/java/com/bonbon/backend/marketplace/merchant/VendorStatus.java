package com.bonbon.backend.marketplace.merchant;

/**
 * Shop lifecycle (open-shop.md): DRAFT → PENDING → APPROVED | REJECTED(reason); REJECTED → (edit, resubmit)
 * → PENDING; SUSPENDED after approval; CLOSING when its owner asks to close it, CLOSED once nothing is left to settle.
 */
public enum VendorStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED,
    CLOSING,
    CLOSED;

    /** The wizard may change the application only before submission or after a rejection. */
    public boolean editableByWizard() {
        return this == DRAFT || this == REJECTED;
    }
}
