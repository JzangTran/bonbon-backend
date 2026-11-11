package com.bonbon.backend.identity.legal;

import java.util.List;
import java.util.UUID;

/**
 * Who a document concerns, known from consent evidence alone: the people who have accepted the terms of that kind (customers for
 * the customer terms, shop owners for the seller terms, both for the privacy policy).
 */
public interface LegalAudience {

    /** {@code audience} is CUSTOMER or SHOP, the same two sides notifications use. */
    record Recipient(UUID principalId, String audience) {
    }

    List<Recipient> of(DocumentType type);
}
