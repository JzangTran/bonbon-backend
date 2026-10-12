package com.bonbon.backend.legal;

import java.time.Instant;
import java.util.UUID;

/**
 * A version was published, will take effect on {@code effectiveAt} and, when {@code material}, must be accepted again. The affected
 * people are found with {@link LegalAudience}. Published inside the administrator's transaction.
 */
public record LegalDocumentPublished(UUID documentId, DocumentType type, int version, String title, String summary, Instant effectiveAt, boolean material) {
}
