package com.bonbon.backend.merchant;

/**
 * Shop lifecycle (open-shop.md): DRAFT → PENDING → APPROVED | REJECTED(reason); REJECTED → (edit, resubmit)
 * → PENDING; SUSPENDED after approval; CLOSED by its owner.
 */
public enum VendorStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED,
    CLOSED;

    /** The wizard may change the application only before submission or after a rejection. */
    public boolean editableByWizard() {
        return this == DRAFT || this == REJECTED;
    }
}
